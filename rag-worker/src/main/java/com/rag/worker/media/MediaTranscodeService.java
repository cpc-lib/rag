package com.rag.worker.media;

import com.rag.worker.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.worker.infrastructure.persistence.mapper.LibraryFileMapper;
import com.rag.worker.infrastructure.storage.MinioStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 文件库视频转码：ffprobe 探测分辨率/帧率 → 保持源画质单档 HLS（不缩放、锁源帧率）。
 * 源为 H.264+AAC 时 -c copy 直切（不重编码，秒级）；其余长视频按时间轴 3 段并行 NVENC 硬编后 concat 直拷封装，
 * 短视频走单次 NVENC（-cq 画质模式，帧全程驻留 GPU），失败逐级回退 CPU 软编。
 * 产物上传 MinIO {objectKey}.hls/（master.m3u8 + 分片），回写 library_file.playback_key/status。
 */
@Service
@Slf4j
public class MediaTranscodeService {

    /** 浏览器可直解的音频编码（HLS ts 容器）。 */
    private static final Set<String> AUDIO_OK = Set.of("aac", "mp3");
    private static final String ST_READY = "READY";
    private static final String ST_FAILED = "FAILED";
    /** 并行分段转码：时长 >= 该值才切段（短视频直接串行，避免进程开销）。 */
    private static final double PARALLEL_MIN_DURATION_SEC = 60;
    /** HLS 分片目标时长（秒），切段点对齐其整数倍网格，保证 concat 直拷后分片均匀。 */
    private static final int SEG_GRID_SEC = 6;

    private final LibraryFileMapper libraryFileMapper;
    private final MinioStorage minioStorage;
    private final MediaProgressPublisher progressPublisher;
    private final String ffmpegPath;
    private final String ffprobePath;
    /** 并行分段转码的分段数（M4000 实测 3 路并发可榨干 NVENC：聚合约 4x 单路速度）。 */
    private final int transcodeParallel;
    /** 运行中的 ffmpeg 进程（并行转码时每个文件可有多个；停止转码时全部强杀）。 */
    private final java.util.Map<Long, List<Process>> runningProcesses = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已请求停止的 fileId：未起进程时（下载/探测阶段）在阶段边界中止。 */
    private final Set<Long> stopRequested = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 用户主动停止转码（区别于转码失败，不置 FAILED）。 */
    public static class StoppedException extends RuntimeException {
        public StoppedException(String message) {
            super(message);
        }
    }

    /** 请求停止：已起 ffmpeg 进程则全部强杀，否则标记后在阶段边界中止。 */
    public void requestStop(long fileId) {
        stopRequested.add(fileId);
        List<Process> ps = runningProcesses.get(fileId);
        if (ps != null) {
            ps.forEach(Process::destroyForcibly);
        }
    }

    private void checkStopped(long fileId) {
        if (stopRequested.contains(fileId)) {
            throw new StoppedException("转码已停止 fileId=" + fileId);
        }
    }

    public MediaTranscodeService(LibraryFileMapper libraryFileMapper,
                                 MinioStorage minioStorage,
                                 MediaProgressPublisher progressPublisher,
                                 @Value("${rag.media.ffmpeg-path:ffmpeg}") String ffmpegPath,
                                 @Value("${rag.media.transcode-parallel:3}") int transcodeParallel) {
        this.libraryFileMapper = libraryFileMapper;
        this.minioStorage = minioStorage;
        this.progressPublisher = progressPublisher;
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffmpegPath.replace("ffmpeg.exe", "ffprobe.exe");
        this.transcodeParallel = Math.max(1, transcodeParallel);
    }

    /** 执行转码：READY 幂等跳过；产物就绪回写 READY，异常由调用方置 FAILED。
     *  输入走 MinIO 预签名 URL 直读（ffprobe/ffmpeg 支持 HTTP 输入，省下载落盘）。 */
    public void transcode(long fileId) throws Exception {
        LibraryFileEntity e = libraryFileMapper.selectById(fileId);
        if (e == null) {
            log.warn("转码目标不存在 fileId={}", fileId);
            return;
        }
        if (ST_READY.equals(e.getPlaybackStatus())) {
            return;
        }
        // 新一轮转码开始，清掉可能残留的历史停止标记
        stopRequested.remove(fileId);
        String url = minioStorage.presignUrl(e.getObjectKey());
        if (url == null) {
            throw new IllegalStateException("预签名失败 objectKey=" + e.getObjectKey());
        }
        Path dir = Files.createTempDirectory("rag-hls-");
        try {
            log.info("转码开始 file={} id={}（预签名 URL 直读，{}MB）", e.getFileName(), fileId,
                    (e.getFileSize() == null ? 0 : e.getFileSize()) / 1024 / 1024);
            Probe probe = probe(url);
            log.info("编码探测 file={} vcodec={} acodec={} 分辨率={}x{} 帧率={} duration={}s", e.getFileName(),
                    probe.vcodec(), probe.acodec(), probe.width(), probe.height(),
                    String.format(java.util.Locale.US, "%.2f", probe.fps()), probe.durationSec());
            checkStopped(fileId);
            Path outDir = dir.resolve("hls");
            Files.createDirectories(outDir);
            boolean h264 = "h264".equals(probe.vcodec());
            boolean audioOk = probe.acodec() == null || AUDIO_OK.contains(probe.acodec());
            if (h264 && audioOk) {
                // 直切：不重编码，单档媒体播放列表即 master.m3u8
                // 注意：var_stream_map/多输出在 Windows 上用绝对路径报 Permission denied，一律相对路径 + 工作目录
                log.info("file={} 源为 H.264+AAC，-c copy 直切 HLS（不重编码）", e.getFileName());
                runFfmpeg(dir, outDir, List.of(ffmpegPath, "-y", "-i", url,
                        "-c", "copy", "-f", "hls", "-hls_time", "6", "-hls_playlist_type", "vod",
                        "-hls_segment_filename", "seg_%03d.ts",
                        "master.m3u8"), probe.durationSec(), e);
            } else {
                transcodeSource(url, outDir, dir, probe, e);
            }

            // 清理旧产物后整体上传（重转码时避免残留旧分片）
            String prefix = e.getObjectKey() + ".hls/";
            minioStorage.deletePrefix(prefix);
            long uploaded = uploadDir(outDir, prefix);
            log.info("产物上传完成 file={} 文件数={}", e.getFileName(), uploaded);

            e.setPlaybackKey(prefix + "master.m3u8");
            e.setPlaybackStatus(ST_READY);
            e.setPlaybackProgress(100);
            e.setVideoWidth(probe.width() > 0 ? probe.width() : null);
            e.setVideoHeight(probe.height() > 0 ? probe.height() : null);
            libraryFileMapper.updateById(e);
            progressPublisher.publish(e.getId(), ST_READY, 100);
            log.info("转码完成 file={} id={} 产物前缀={}", e.getFileName(), fileId, prefix);
        } finally {
            deleteRecursively(dir);
        }
    }

    /** 源画质转码：保持源分辨率与帧率，画质模式（NVENC -cq / x264 -crf），只出单档 HLS。
     *  回退链：NVENC 时间轴并行分段（长视频）→ GPU 解码+NVENC → CPU 解码+NVENC → CPU 软编 x264。 */
    private void transcodeSource(String inputUrl, Path outDir, Path workDir, Probe probe,
                                 LibraryFileEntity entity) throws Exception {
        // 长视频优先按时间轴切段并行转码：单路 ffmpeg 只能跑满约一半 NVENC（M4000 实测），
        // 3 路并发聚合速度约 4x；任何一段失败都整体回退下方串行链路
        if (nvencAvailable() && probe.durationSec() >= PARALLEL_MIN_DURATION_SEC) {
            try {
                parallelTranscode(inputUrl, outDir, workDir, probe, entity);
                return;
            } catch (StoppedException se) {
                throw se;
            } catch (Exception ex) {
                log.warn("并行分段转码失败，回退串行链路 file={}: {}", entity.getFileName(), ex.getMessage());
                deleteRecursively(outDir);
                Files.createDirectories(outDir);
            }
        }
        if (!nvencAvailable()) {
            log.info("源画质转码 file={} 分辨率={}x{} 帧率={} 编码器=x264 音频={}",
                    entity.getFileName(), probe.width(), probe.height(),
                    String.format(java.util.Locale.US, "%.2f", probe.fps()),
                    probe.acodec() != null ? "aac" : "无音轨");
            runFfmpeg(workDir, outDir, buildCmd(inputUrl, probe, "x264", false), probe.durationSec(), entity);
            return;
        }
        // 提速关键：4K HEVC 解码很吃 CPU（常是整体瓶颈），优先 GPU 硬件解码，把 CPU 留给滤镜
        try {
            log.info("源画质转码 file={} 分辨率={}x{} 帧率={} 编码器=nvenc（GPU 解码）", entity.getFileName(),
                    probe.width(), probe.height(), String.format(java.util.Locale.US, "%.2f", probe.fps()));
            runFfmpeg(workDir, outDir, buildCmd(inputUrl, probe, "nvenc", true), probe.durationSec(), entity);
            return;
        } catch (IllegalStateException ex) {
            log.warn("GPU 解码转码失败，回退 CPU 解码+NVENC: {}", ex.getMessage());
            deleteRecursively(outDir);
            Files.createDirectories(outDir);
        }
        try {
            log.info("源画质转码 file={} 编码器=nvenc（CPU 解码）", entity.getFileName());
            runFfmpeg(workDir, outDir, buildCmd(inputUrl, probe, "nvenc", false), probe.durationSec(), entity);
            return;
        } catch (IllegalStateException ex) {
            log.warn("NVENC 转码失败，本进程内停用 NVENC 并回退 CPU 软编: {}", ex.getMessage());
            // 驱动过老（nvenc API 版本不满足）等场景：避免每个文件都白跑一次注定失败的 NVENC
            nvencSupported = false;
            deleteRecursively(outDir);
            Files.createDirectories(outDir);
        }
        log.info("源画质转码 file={} 编码器=x264", entity.getFileName());
        runFfmpeg(workDir, outDir, buildCmd(inputUrl, probe, "x264", false), probe.durationSec(), entity);
    }

    /**
     * 时间轴并行分段转码：把视频按 SEG_GRID_SEC 网格切成 K 段（K<=transcodeParallel），
     * K 个 ffmpeg 同时各自硬解+NVENC 输出 MPEG-TS 分片，再用 concat 解复用器 -c copy 秒级封装为统一 HLS。
     * 切段长度取 6 秒整数倍，段内关键帧按帧间隔固定（mpegts+seek 下 -force_key_frames 时间表达式不可靠），
     * 保证 concat 直拷后 HLS 分片仍均匀 ≤6s。任一段失败抛异常，由上层回退串行链路。
     */
    private void parallelTranscode(String inputUrl, Path outDir, Path workDir, Probe probe,
                                   LibraryFileEntity entity) throws Exception {
        double duration = probe.durationSec();
        // 理想段长向上取整到 6s 网格：各切段起点都落在全局 6s 关键帧网格上，分段数不超过并行度
        double idealLen = duration / transcodeParallel;
        double chunkLen = Math.ceil(idealLen / SEG_GRID_SEC) * SEG_GRID_SEC;
        List<double[]> ranges = new ArrayList<>();
        for (double start = 0; start < duration - 1e-3; start += chunkLen) {
            ranges.add(new double[]{start, Math.min(chunkLen, duration - start)});
        }
        int k = ranges.size();
        // 30fps→180 帧一个关键帧；29.97 等分数帧率取整后段内 6.006s，浏览器 HLS 兼容
        int gopFrames = Math.max(1, (int) Math.round(probe.fps() * SEG_GRID_SEC));

        Path chunksDir = outDir.resolve("_chunks");
        Files.createDirectories(chunksDir);
        log.info("并行分段转码 file={} 段数={} 段长≈{}s 编码器=nvenc（{}x 并发）",
                entity.getFileName(), k, String.format(java.util.Locale.US, "%.0f", chunkLen), k);

        double[] weights = new double[k];
        for (int i = 0; i < k; i++) {
            weights[i] = ranges.get(i)[1] / duration;
        }
        ParallelProgress progress = new ParallelProgress(entity, weights);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(k);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        List<String> cmd = buildChunkCmd(inputUrl, probe,
                                ranges.get(idx)[0], ranges.get(idx)[1], gopFrames, idx);
                        runProcess(chunksDir, workDir.resolve("chunk_" + idx + ".log"), cmd,
                                ranges.get(idx)[1], entity, 0, 100, pct -> progress.update(idx, pct));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }));
            }
            // 汇总异常：首个失败段立即强杀其余段，快速回退串行（不等它们跑完）
            Exception first = null;
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() instanceof RuntimeException re && re.getCause() != null
                            ? re.getCause() : ee.getCause();
                    if (first == null) {
                        first = cause instanceof Exception e ? e : new RuntimeException(cause);
                        List<Process> siblings = runningProcesses.get(entity.getId());
                        if (siblings != null) {
                            siblings.forEach(Process::destroyForcibly);
                        }
                    }
                }
            }
            if (first != null) {
                if (stopRequested.contains(entity.getId())) {
                    throw new StoppedException("转码已停止 fileId=" + entity.getId());
                }
                throw first;
            }
        } finally {
            pool.shutdownNow();
        }
        checkStopped(entity.getId());

        // concat 清单（路径相对清单所在目录）
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < k; i++) {
            list.append("file 'chunk_").append(i).append(".ts'\n");
        }
        Files.writeString(chunksDir.resolve("list.txt"), list.toString(), StandardCharsets.UTF_8);
        // -c copy 直拷封装 HLS：不重编码，秒级完成（无时长信息，进度条保持在并行段结束时的位置）
        List<String> concatCmd = new ArrayList<>(List.of(ffmpegPath, "-y",
                "-f", "concat", "-safe", "0", "-i", "_chunks/list.txt",
                "-c", "copy", "-f", "hls", "-hls_time", String.valueOf(SEG_GRID_SEC),
                "-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
                "-hls_segment_filename", "seg_%03d.ts", "master.m3u8"));
        runProcess(outDir, workDir.resolve("concat.log"), concatCmd, 0, entity, 0, 100, pct -> {
        });
        deleteRecursively(chunksDir);
        log.info("并行分段转码封装完成 file={}", entity.getFileName());
    }

    /** 构建单段转码命令：-ss 输入快seek（转码时 ffmpeg 自动精确到目标时间戳），输出 mpegts 连续分片。 */
    private List<String> buildChunkCmd(String inputUrl, Probe probe, double startSec, double lengthSec,
                                       int gopFrames, int chunkIndex) {
        boolean hasAudio = probe.acodec() != null;
        List<String> cmd = new ArrayList<>(List.of(ffmpegPath, "-y",
                // HEVC 等本卡不支持硬解的编码自动回退软解，解码帧经 hwupload_cuda 上送 NVENC，两种解码都兼容
                "-hwaccel", "cuda",
                "-ss", String.format(java.util.Locale.US, "%.3f", startSec),
                "-i", inputUrl,
                "-t", String.format(java.util.Locale.US, "%.3f", lengthSec),
                "-map", "0:v:0"));
        if (hasAudio) {
            cmd.addAll(List.of("-map", "0:a:0?"));
        }
        cmd.addAll(List.of("-r", String.valueOf(probe.fps())));
        // 帧间隔固定关键帧（比时间表达式在 mpegts 输出下更可靠），与 6s HLS 网格对齐
        cmd.addAll(List.of("-g", String.valueOf(gopFrames), "-keyint_min", String.valueOf(gopFrames)));
        cmd.addAll(List.of("-vf", "format=yuv420p,hwupload_cuda"));
        if (hasAudio) {
            // 切段边界补偿 AAC 编码 priming，避免拼接处音画累计漂移
            cmd.addAll(List.of("-af", "aresample=async=1"));
        }
        // 与串行 NVENC 路径同一画质/码控参数
        cmd.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p4", "-cq", "23",
                "-maxrate", "20M", "-bufsize", "40M"));
        if (hasAudio) {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(List.of("-f", "mpegts", "chunk_" + chunkIndex + ".ts"));
        return cmd;
    }

    /** 多段并行进度聚合：按各段时长权重汇总成整体百分比，500ms 节流写库 + WebSocket 推送。 */
    private final class ParallelProgress {
        private final LibraryFileEntity entity;
        private final double[] weights;
        private final double[] fractions;
        private long lastUpdate;
        private int lastPct = -1;

        ParallelProgress(LibraryFileEntity entity, double[] weights) {
            this.entity = entity;
            this.weights = weights;
            this.fractions = new double[weights.length];
        }

        synchronized void update(int chunk, int pct) {
            fractions[chunk] = pct / 100.0;
            double done = 0;
            for (int i = 0; i < fractions.length; i++) {
                done += weights[i] * fractions[i];
            }
            int global = (int) Math.min(99, Math.round(done * 100));
            long now = System.currentTimeMillis();
            if (global != lastPct && now - lastUpdate >= 500) {
                lastPct = global;
                lastUpdate = now;
                try {
                    entity.setPlaybackProgress(global);
                    libraryFileMapper.updateById(entity);
                    progressPublisher.publish(entity.getId(), "PROCESSING", global);
                } catch (Exception ignored) {
                    // 进度回写失败不影响转码主流程
                }
            }
        }
    }

    /** 构建转码命令：保持源分辨率与源帧率；hwaccel=true 时帧全程驻留 GPU（CUDA 解码 + scale_cuda 格式转换）。 */
    private List<String> buildCmd(String inputUrl, Probe probe, String encoder, boolean hwaccel) {
        boolean hasAudio = probe.acodec() != null;
        List<String> cmd = new ArrayList<>(List.of(ffmpegPath, "-y"));
        if (hwaccel) {
            // 解码帧保留在显存（不回传系统内存），scale_cuda 在 GPU 上做像素格式转换后直接喂 NVENC
            cmd.addAll(List.of("-hwaccel", "cuda", "-hwaccel_output_format", "cuda"));
        }
        cmd.addAll(List.of("-i", inputUrl, "-map", "0:v:0"));
        if (hasAudio) {
            cmd.addAll(List.of("-map", "0:a:0"));
        }
        // 锁帧率（HLS 浏览器兼容性更稳），不缩放，保持源分辨率
        cmd.addAll(List.of("-r", String.valueOf(probe.fps())));
        // 每 6 秒强制关键帧，与 -hls_time 对齐：保证分片切点均匀，避免分片时长/大小漂移
        cmd.addAll(List.of("-force_key_frames", "expr:gte(t,n_forced*6)"));
        // 10-bit/其他像素格式转 8-bit yuv420p：NVENC 只支持 8-bit 编码，不加会报 "10 bit encode not supported"；
        // 8-bit 源此滤镜为透传无副作用，浏览器播放也要求 yuv420p
        cmd.addAll(List.of("-vf", hwaccel ? "scale_cuda=format=yuv420p" : "format=yuv420p"));
        if ("nvenc".equals(encoder)) {
            // NVENC 质量模式：-cq 23（0-51，越低画质越高；23 为均衡点）
            // p4 比 p1 压缩率明显更好（同质量码率更低）；maxrate 封顶防止 4K60 复杂场景码率失控
            // （实测 ultrafast/p1 会把 6.7Mbps 的 HEVC 源膨胀到 35~85Mbps，单个 6 秒分片近百 MB 导致 HLS 播放中断）
            cmd.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p4", "-cq", "23",
                    "-maxrate", "20M", "-bufsize", "40M"));
        } else {
            // CPU 质量模式：-crf 23；veryfast 压缩率显著优于 ultrafast（ultrafast 在 4K60 上码率会膨胀 5~13 倍），
            // 叠加 maxrate 20M 封顶，6 秒分片约 15MB，兼顾画质与浏览器 HLS 加载
            cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                    "-maxrate", "20M", "-bufsize", "40M"));
        }
        if (hasAudio) {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(List.of("-f", "hls", "-hls_time", "6", "-hls_playlist_type", "vod",
                "-hls_flags", "independent_segments",
                "-hls_segment_filename", "seg_%03d.ts",
                "master.m3u8"));
        return cmd;
    }

    /** ffprobe 探测首个视频流编码/分辨率/帧率、首个音频流编码与总时长（秒，用于进度计算）。input 支持本地路径或预签名 URL。 */
    private Probe probe(String input) throws Exception {
        String vline = runFfprobe(input, "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,height,width,r_frame_rate");
        String aline = runFfprobe(input, "-select_streams", "a:0",
                "-show_entries", "stream=codec_name");
        String dline = runFfprobe(input, "-show_entries", "format=duration");
        String vcodec = null;
        int height = 0;
        int width = 0;
        double fps = 0;
        // csv 输出如 "h264,1080,1920,30000/1001"
        if (!vline.isBlank()) {
            String[] parts = vline.trim().split(",");
            vcodec = parts[0];
            if (parts.length > 1) {
                try {
                    height = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    // 高度缺失按 0 处理
                }
            }
            if (parts.length > 2) {
                try {
                    width = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {
                    // 宽度缺失不影响转码
                }
            }
            if (parts.length > 3) {
                fps = parseFps(parts[3].trim());
            }
        }
        if (vcodec == null) {
            throw new IllegalStateException("未探测到视频流");
        }
        if (fps <= 0 || fps > 120) {
            fps = 25;
            log.warn("帧率探测异常，按 25fps 处理");
        }
        String acodec = aline.isBlank() ? null : aline.trim().split(",")[0];
        double durationSec = 0;
        try {
            durationSec = Double.parseDouble(dline.trim());
        } catch (NumberFormatException ignored) {
            // 时长缺失则不汇报进度
        }
        return new Probe(vcodec, acodec, height, width, fps, durationSec);
    }

    /** 解析 ffprobe 分数帧率（如 30000/1001 → 29.97），失败返回 0。 */
    private double parseFps(String raw) {
        try {
            String[] ab = raw.split("/");
            double a = Double.parseDouble(ab[0]);
            double b = ab.length > 1 ? Double.parseDouble(ab[1]) : 1;
            return b > 0 ? a / b : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Probe(String vcodec, String acodec, int height, int width, double fps, double durationSec) {
    }

    private String runFfprobe(String input, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ffprobePath, "-v", "error", "-of", "csv=p=0"));
        cmd.addAll(List.of(args));
        cmd.add(input);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("ffprobe 执行失败");
        }
        return out.trim();
    }

    private volatile Boolean nvencSupported;

    /** 检测 NVENC 是否可用（结果缓存）。 */
    private boolean nvencAvailable() {
        if (nvencSupported == null) {
            synchronized (this) {
                if (nvencSupported == null) {
                    boolean ok = false;
                    try {
                        Process p = new ProcessBuilder(ffmpegPath, "-hide_banner", "-h", "encoder=h264_nvenc")
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        ok = p.waitFor() == 0;
                    } catch (Exception ignored) {
                        // 探测失败按不可用处理
                    }
                    nvencSupported = ok;
                    log.info("NVENC 硬件编码可用: {}", ok);
                }
            }
        }
        return nvencSupported;
    }

    /**
     * 串行转码执行入口：stderr 落工作目录 ffmpeg.log；stdout 接 -progress pipe:1，
     * 按 out_time_ms（微秒）/ 总时长计算进度，节流回写 library_file.playback_progress。
     */
    private void runFfmpeg(Path workDir, Path processDir, List<String> cmd, double durationSec,
                           LibraryFileEntity entity) throws Exception {
        int[] lastMilestone = {0};
        runProcess(processDir, workDir.resolve("ffmpeg.log"), cmd, durationSec, entity, 0, 100, pct -> {
            try {
                entity.setPlaybackProgress(pct);
                libraryFileMapper.updateById(entity);
                // Redis 广播进度事件，rag-api 经 WebSocket 推给前端
                progressPublisher.publish(entity.getId(), "PROCESSING", pct);
                // 每跨 10% 打一次里程碑日志，避免刷屏
                if (pct / 10 > lastMilestone[0] / 10) {
                    log.info("转码进度 file={} {}%", entity.getFileName(), pct);
                }
                lastMilestone[0] = pct;
            } catch (Exception ignored) {
                // 进度回写失败不影响转码主流程
            }
        });
    }

    /**
     * 通用 ffmpeg 进程执行：相对路径输出（HLS 在 Windows 下绝对路径会 Permission denied），
     * 进程登记到运行表（同一文件并行段可有多个进程），进度百分比经 onPct 回调上报（由调用方决定写库/聚合方式）。
     */
    private void runProcess(Path processDir, Path logFile, List<String> cmd, double durationSec,
                            LibraryFileEntity entity, double progressBase, double progressSpan,
                            java.util.function.IntConsumer onPct) throws Exception {
        List<String> full = new ArrayList<>(cmd);
        full.addAll(full.indexOf("-y") + 1, List.of("-nostats", "-progress", "pipe:1"));
        log.info("执行 ffmpeg: {}", String.join(" ", full).replaceAll("https?://\\S+", "<presigned-url>"));
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.directory(processDir.toFile());
        pb.redirectError(logFile.toFile());
        Process process = pb.start();
        List<Process> registered = runningProcesses.computeIfAbsent(entity.getId(),
                k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        registered.add(process);
        try {
            Thread reader = new Thread(() -> reportProgress(process, durationSec, progressBase, progressSpan, onPct));
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(60, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
            }
            // 用户停止优先于失败判定（强杀进程的 exit code 无意义）
            if (stopRequested.contains(entity.getId())) {
                throw new StoppedException("转码已停止 fileId=" + entity.getId());
            }
            if (!finished || process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg 处理失败，exit=" + (finished ? process.exitValue() : "超时")
                        + "，日志尾部: " + tailLog(logFile));
            }
        } finally {
            registered.remove(process);
            runningProcesses.remove(entity.getId(), registered);
        }
    }

    /** 读取 ffmpeg 日志尾部（约 2KB），失败原因随异常抛出可见。 */
    private String tailLog(Path logFile) {
        try {
            byte[] bytes = Files.readAllBytes(logFile);
            int from = Math.max(0, bytes.length - 2048);
            return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }

    /** 读取 ffmpeg -progress 输出，折算百分比后回调 onPct（≥500ms 且百分比变化才回调，避免刷库）。 */
    private void reportProgress(Process process, double durationSec,
                                double progressBase, double progressSpan,
                                java.util.function.IntConsumer onPct) {
        if (durationSec <= 0) {
            // 无时长也要把 stdout 排空，避免管道写满阻塞 ffmpeg
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (br.readLine() != null) {
                    // 丢弃
                }
            } catch (Exception ignored) {
            }
            return;
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            long lastUpdate = 0;
            int lastPct = -1;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("out_time_ms=")) {
                    continue;
                }
                long micros;
                try {
                    micros = Long.parseLong(line.substring("out_time_ms=".length()).trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                // ffmpeg 的 out_time_ms 实为微秒；折算到 [base, base+span] 区间，封顶 99，100 由转码完成后统一回写
                double raw = micros / 1_000_000.0 / durationSec * 100;
                int pct = (int) Math.min(99, Math.round(progressBase + progressSpan * Math.min(100, raw) / 100));
                long now = System.currentTimeMillis();
                if (pct != lastPct && now - lastUpdate >= 500) {
                    lastPct = pct;
                    lastUpdate = now;
                    onPct.accept(pct);
                }
            }
        } catch (Exception ignored) {
            // 进程结束/管道关闭属正常
        }
    }

    /** 产物目录整体上传：{prefix}{相对路径}，m3u8/ts 显式声明内容类型。返回上传文件数。 */
    private long uploadDir(Path outDir, String prefix) throws Exception {
        try (Stream<Path> paths = Files.walk(outDir)) {
            long count = 0;
            for (Path p : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                String rel = outDir.relativize(p).toString().replace('\\', '/');
                String name = p.getFileName().toString().toLowerCase();
                String contentType = name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                        : name.endsWith(".ts") ? "video/mp2t" : "application/octet-stream";
                try (InputStream in = Files.newInputStream(p)) {
                    minioStorage.upload(prefix + rel, in, Files.size(p), contentType);
                }
                count++;
            }
            return count;
        }
    }

    /** 源文件下载：≥64MB 走 16 路并行 Range 下载（单流打不满带宽时提速数倍），否则单流。 */
    private void downloadSource(String objectKey, Path target, long totalBytes, String fileName) throws Exception {
        int parts = totalBytes >= 64L * 1024 * 1024 ? 16 : 1;
        if (parts <= 1) {
            try (InputStream in = minioStorage.download(objectKey)) {
                copyWithProgress(in, target, totalBytes, fileName);
            }
            return;
        }
        log.info("并行下载 file={} 分片数={}", fileName, parts);
        long chunk = totalBytes / parts;
        java.util.concurrent.atomic.AtomicLong copied = new java.util.concurrent.atomic.AtomicLong();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(totalBytes);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(parts);
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < parts; i++) {
                    long offset = i * chunk;
                    long length = (i == parts - 1) ? totalBytes - offset : chunk;
                    java.nio.channels.FileChannel ch = raf.getChannel();
                    futures.add(pool.submit(() -> {
                        try (InputStream in = minioStorage.downloadRange(objectKey, offset, length)) {
                            byte[] buf = new byte[1024 * 1024];
                            long pos = offset;
                            long remain = length;
                            while (remain > 0) {
                                int n = in.read(buf, 0, (int) Math.min(buf.length, remain));
                                if (n < 0) {
                                    break;
                                }
                                // FileChannel 定位写线程安全，无需加锁
                                ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos);
                                pos += n;
                                remain -= n;
                                copied.addAndGet(n);
                            }
                            if (remain > 0) {
                                throw new IllegalStateException("分片下载不完整 offset=" + offset + " 缺 " + remain + " 字节");
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                        return null;
                    }));
                }
                // 主线程打进度（5 秒一条）
                while (!futures.stream().allMatch(java.util.concurrent.Future::isDone)) {
                    Thread.sleep(5000);
                    log.info("下载进度 file={} {}/{}MB ({}%)", fileName,
                            copied.get() / 1024 / 1024, totalBytes / 1024 / 1024, copied.get() * 100 / totalBytes);
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    f.get();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** 单流下载：每 5 秒打一次进度日志，大文件下载可观测、卡死可定位。 */
    private void copyWithProgress(InputStream in, Path target, long totalBytes, String fileName) throws Exception {
        try (java.io.OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1024 * 1024];
            long copied = 0;
            long lastLog = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                copied += n;
                long now = System.currentTimeMillis();
                if (now - lastLog >= 5000) {
                    lastLog = now;
                    if (totalBytes > 0) {
                        log.info("下载进度 file={} {}/{}MB ({}%)", fileName,
                                copied / 1024 / 1024, totalBytes / 1024 / 1024, copied * 100 / totalBytes);
                    } else {
                        log.info("下载进度 file={} {}MB", fileName, copied / 1024 / 1024);
                    }
                }
            }
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}

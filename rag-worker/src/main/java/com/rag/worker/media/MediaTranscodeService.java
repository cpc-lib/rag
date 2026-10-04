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
 * 源为 H.264+AAC 时 -c copy 直切（不重编码，秒级）；其余优先 NVENC 硬编（-cq 画质模式），失败回退 CPU 软编。
 * 产物上传 MinIO {objectKey}.hls/（master.m3u8 + 分片），回写 library_file.playback_key/status。
 */
@Service
@Slf4j
public class MediaTranscodeService {

    /** 浏览器可直解的音频编码（HLS ts 容器）。 */
    private static final Set<String> AUDIO_OK = Set.of("aac", "mp3");
    private static final String ST_READY = "READY";
    private static final String ST_FAILED = "FAILED";

    private final LibraryFileMapper libraryFileMapper;
    private final MinioStorage minioStorage;
    private final MediaProgressPublisher progressPublisher;
    private final String ffmpegPath;
    private final String ffprobePath;
    /** 运行中的 ffmpeg 进程（停止转码时强杀）。 */
    private final java.util.Map<Long, Process> runningProcesses = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已请求停止的 fileId：未起进程时（下载/探测阶段）在阶段边界中止。 */
    private final Set<Long> stopRequested = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 用户主动停止转码（区别于转码失败，不置 FAILED）。 */
    public static class StoppedException extends RuntimeException {
        public StoppedException(String message) {
            super(message);
        }
    }

    /** 请求停止：已起 ffmpeg 进程则强杀，否则标记后在阶段边界中止。 */
    public void requestStop(long fileId) {
        stopRequested.add(fileId);
        Process p = runningProcesses.get(fileId);
        if (p != null) {
            p.destroyForcibly();
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
                                 @Value("${rag.media.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.libraryFileMapper = libraryFileMapper;
        this.minioStorage = minioStorage;
        this.progressPublisher = progressPublisher;
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffmpegPath.replace("ffmpeg.exe", "ffprobe.exe");
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
            libraryFileMapper.updateById(e);
            progressPublisher.publish(e.getId(), ST_READY, 100);
            log.info("转码完成 file={} id={} 产物前缀={}", e.getFileName(), fileId, prefix);
        } finally {
            deleteRecursively(dir);
        }
    }

    /** 源画质转码：保持源分辨率与帧率，画质模式（NVENC -cq / x264 -crf），只出单档 HLS。
     *  回退链：GPU 解码+NVENC → CPU 解码+NVENC → CPU 软编 x264。 */
    private void transcodeSource(String inputUrl, Path outDir, Path workDir, Probe probe,
                                 LibraryFileEntity entity) throws Exception {
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

    /** 构建转码命令：保持源分辨率与源帧率；hwaccel=true 时用 GPU 解码（不支持的编码自动回退软解）。 */
    private List<String> buildCmd(String inputUrl, Probe probe, String encoder, boolean hwaccel) {
        boolean hasAudio = probe.acodec() != null;
        List<String> cmd = new ArrayList<>(List.of(ffmpegPath, "-y"));
        if (hwaccel) {
            cmd.addAll(List.of("-hwaccel", "cuda"));
        }
        cmd.addAll(List.of("-i", inputUrl, "-map", "0:v:0"));
        if (hasAudio) {
            cmd.addAll(List.of("-map", "0:a:0"));
        }
        // 锁帧率（HLS 浏览器兼容性更稳），不缩放，保持源分辨率
        cmd.addAll(List.of("-r", String.valueOf(probe.fps())));
        // 10-bit/其他像素格式转 8-bit yuv420p：NVENC 只支持 8-bit 编码，不加会报 "10 bit encode not supported"；
        // 8-bit 源此滤镜为透传无副作用，浏览器播放也要求 yuv420p
        cmd.addAll(List.of("-vf", "format=yuv420p"));
        if ("nvenc".equals(encoder)) {
            // NVENC 画质模式：-cq 23（0-51，越低画质越高；23 为均衡点）
            cmd.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p1", "-cq", "23"));
        } else {
            // CPU 画质模式：-crf 23（ultrafast 预设速度最快，crf 23 画质均衡）
            cmd.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-crf", "23"));
        }
        if (hasAudio) {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(List.of("-f", "hls", "-hls_time", "6", "-hls_playlist_type", "vod",
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
     * 执行 ffmpeg：stderr 落工作目录 ffmpeg.log；stdout 接 -progress pipe:1 输出，
     * 由读取线程按 out_time_ms（微秒）/ 总时长计算进度，节流回写 library_file.playback_progress。
     */
    private void runFfmpeg(Path workDir, Path processDir, List<String> cmd, double durationSec,
                           LibraryFileEntity entity) throws Exception {
        runFfmpeg(workDir, processDir, cmd, durationSec, entity, 0, 100);
    }

    /** progressBase/progressSpan：逐档串行时把单档进度折算到整体进度区间。 */
    private void runFfmpeg(Path workDir, Path processDir, List<String> cmd, double durationSec,
                           LibraryFileEntity entity, double progressBase, double progressSpan) throws Exception {
        List<String> full = new ArrayList<>(cmd);
        full.addAll(full.indexOf("-y") + 1, List.of("-nostats", "-progress", "pipe:1"));
        log.info("执行 ffmpeg: {}", String.join(" ", full).replaceAll("https?://\\S+", "<presigned-url>"));
        Path ffmpegLog = workDir.resolve("ffmpeg.log");
        ProcessBuilder pb = new ProcessBuilder(full);
        // HLS 输出用相对路径（Windows 上 var_stream_map + 绝对路径会 Permission denied），故指定工作目录
        pb.directory(processDir.toFile());
        pb.redirectError(ffmpegLog.toFile());
        Process process = pb.start();
        runningProcesses.put(entity.getId(), process);
        try {
            Thread reader = new Thread(() -> reportProgress(process, durationSec, entity, progressBase, progressSpan));
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
                        + "，日志尾部: " + tailLog(ffmpegLog));
            }
        } finally {
            runningProcesses.remove(entity.getId());
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

    /** 读取 ffmpeg -progress 输出并回写转码进度（≥500ms 且百分比变化才写库，避免刷库）。 */
    private void reportProgress(Process process, double durationSec, LibraryFileEntity entity,
                                double progressBase, double progressSpan) {
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
            int lastMilestone = (int) progressBase;
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
                // ffmpeg 的 out_time_ms 实为微秒；单档进度折算到 [base, base+span] 区间，封顶 99，100 由转码完成后统一回写
                double raw = micros / 1_000_000.0 / durationSec * 100;
                int pct = (int) Math.min(99, Math.round(progressBase + progressSpan * Math.min(100, raw) / 100));
                long now = System.currentTimeMillis();
                if (pct != lastPct && now - lastUpdate >= 500) {
                    lastPct = pct;
                    lastUpdate = now;
                    try {
                        entity.setPlaybackProgress(pct);
                        libraryFileMapper.updateById(entity);
                        // Redis 广播进度事件，rag-api 经 WebSocket 推给前端
                        progressPublisher.publish(entity.getId(), "PROCESSING", pct);
                        // 每跨 10% 打一次里程碑日志，避免刷屏
                        if (pct / 10 > lastMilestone / 10) {
                            log.info("转码进度 file={} {}%", entity.getFileName(), pct);
                        }
                        lastMilestone = pct;
                    } catch (Exception ignored) {
                        // 进度回写失败不影响转码主流程
                    }
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

package com.rag.api.interfaces;

import com.rag.api.application.FileLibraryService;
import com.rag.api.common.ApiResult;
import com.rag.api.infrastructure.persistence.entity.LibraryFileEntity;
import com.rag.api.infrastructure.storage.MinioStorage;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/library/files")
@RequiredArgsConstructor
public class FileLibraryController {

    private final FileLibraryService fileLibraryService;
    private final MinioStorage minioStorage;

    /** 文件库列表；keyword 非空时按文件名模糊查询。 */
    @GetMapping
    public ApiResult<List<Dtos.LibraryFileView>> list(
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ApiResult.ok(fileLibraryService.list(keyword));
    }

    /** 文件库直接上传任意文件（bizType=OTHER，可删除）。 */
    @PostMapping
    public ApiResult<Dtos.LibraryFileView> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok(fileLibraryService.uploadDirect(file));
    }

    /** 删除文件库条目（仅字幕翻译保存归档与直接上传的文件可删除）。 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        fileLibraryService.delete(id);
        return ApiResult.ok(null);
    }

    /** 查看文本内容（SRT/VTT 等文本文件）。 */
    @GetMapping("/{id}/content")
    public ApiResult<String> content(@PathVariable long id) {
        return ApiResult.ok(fileLibraryService.content(id));
    }

    /** 读取归档字幕的可编辑条目：优先字幕条备份（字幕主记录删除后仍可用），无备份则解析文件自身。 */
    @GetMapping("/{id}/cues")
    public ApiResult<List<Dtos.SubtitleCue>> cues(@PathVariable long id) {
        return ApiResult.ok(fileLibraryService.cueViews(id));
    }

    /** 直接编辑归档文件：条目覆盖写回该文件本身（不新增版本）。 */
    @PutMapping("/{id}/content")
    public ApiResult<Dtos.LibraryFileView> saveContent(@PathVariable long id,
                                                       @RequestBody @Valid Dtos.SubtitleUpdateReq req) {
        return ApiResult.ok(fileLibraryService.saveCues(id, req.cues()));
    }

    /** 查询播放状态：NONE/PROCESSING/READY/FAILED + 转码进度（纯查询，不触发转码）。 */
    @GetMapping("/{id}/playback")
    public ApiResult<Dtos.PlaybackResp> playback(@PathVariable long id) {
        return ApiResult.ok(fileLibraryService.playback(id));
    }

    /** 开始/重新转码（幂等）：视频且非转码中时投递 Worker 转 HLS。 */
    @PostMapping("/{id}/transcode")
    public ApiResult<Dtos.PlaybackResp> transcode(@PathVariable long id) {
        return ApiResult.ok(fileLibraryService.startTranscode(id));
    }

    /** 停止转码：状态回到 NONE，Worker 强杀 ffmpeg 进程。 */
    @PostMapping("/{id}/transcode/stop")
    public ApiResult<Dtos.PlaybackResp> stopTranscode(@PathVariable long id) {
        return ApiResult.ok(fileLibraryService.stopTranscode(id));
    }

    /** 保存视频播放进度（当前租户+用户+文件维度 upsert），关闭弹窗或定期上报时调用。 */
    @PostMapping("/{id}/playback-position")
    public ApiResult<Void> savePlaybackPosition(@PathVariable long id,
                                                @RequestBody @Valid Dtos.PlaybackPositionReq req) {
        fileLibraryService.savePlaybackPosition(id, req.positionMs(), req.durationMs());
        return ApiResult.ok(null);
    }

    /** 当前用户的视频播放记录列表（按最近播放倒序，最多 100 条）。 */
    @GetMapping("/playback-history")
    public ApiResult<List<Dtos.PlaybackHistoryView>> playbackHistory() {
        return ApiResult.ok(fileLibraryService.listPlaybackHistory());
    }

    /**
     * HLS 播放产物：master.m3u8 / 子播放列表 / ts 分片，按对象前缀 {objectKey}.hls/ 直读 MinIO。
     * hls.js 经 xhrSetup 注入 Authorization 头鉴权，URL 无需 token 参数。
     */
    @GetMapping("/{id}/hls/**")
    public void hls(@PathVariable long id, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        LibraryFileEntity e = fileLibraryService.streamInfo(id);
        String uri = request.getRequestURI();
        String marker = "/hls/";
        int idx = uri.indexOf(marker);
        String rel = idx >= 0 ? uri.substring(idx + marker.length()) : "";
        if (rel.isEmpty() || rel.contains("..")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        response.setContentType(rel.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                : rel.endsWith(".ts") ? "video/mp2t" : "application/octet-stream");
        // 显式声明分片总长度：避免 chunked 传输下播放器无法预知大小，慢网络中误判加载失败
        response.setContentLengthLong(minioStorage.statSize(e.getObjectKey() + ".hls/" + rel));
        try (InputStream in = minioStorage.download(e.getObjectKey() + ".hls/" + rel)) {
            in.transferTo(response.getOutputStream());
        } catch (java.io.IOException ex) {
            // 客户端主动中断（hls.js 切清晰度/跳转/关闭弹窗）属正常行为
            if (ex.getClass().getSimpleName().contains("ClientAbort")) {
                return;
            }
            // 上游读取中途失败要可见：否则播放器只会拿到被截断的分片并卡在播放中途
            // （响应已开始写出，状态码无法再改，只能记录日志并结束连接）
            org.slf4j.LoggerFactory.getLogger(FileLibraryController.class)
                    .warn("HLS 产物读取中断 file={} rel={}: {}", id, rel, ex.getMessage());
        } catch (Exception ex) {
            // 产物不存在（转码未完成/已清理）
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /** 媒体流播放：支持 HTTP Range（视频快进拖动）；浏览器 <video>/<audio> 经 ?token= 鉴权直接拉流。 */
    @GetMapping("/{id}/stream")
    public void stream(@PathVariable long id, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        LibraryFileEntity e = fileLibraryService.streamInfo(id);
        // 已转码（avi/ts → mp4）的播放转码产物；原始格式不可解码时才有 playbackKey
        boolean usePlayback = e.getPlaybackKey() != null && !e.getPlaybackKey().isBlank();
        String objectKey = usePlayback ? e.getPlaybackKey() : e.getObjectKey();
        long total = usePlayback ? minioStorage.statSize(objectKey) : e.getFileSize();
        long start = 0;
        long end = total - 1;
        boolean ranged = false;
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null && range.startsWith("bytes=")) {
            String[] parts = range.substring(6).split("-", 2);
            try {
                if (!parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Math.min(Long.parseLong(parts[1]), total - 1);
                    }
                } else if (parts.length > 1) {
                    // bytes=-N 后缀范围：最后 N 字节
                    start = Math.max(0, total - Long.parseLong(parts[1]));
                }
                ranged = true;
            } catch (NumberFormatException ignored) {
                start = 0;
                end = total - 1;
            }
        }
        if (start > end || start >= total) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total);
            return;
        }
        long length = end - start + 1;
        response.setStatus(ranged ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (ranged) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        response.setContentLengthLong(length);
        response.setContentType(usePlayback ? "video/mp4" : mediaTypeOf(e.getFileName(), e.getContentType()));
        try (InputStream in = minioStorage.downloadRange(objectKey, start, length)) {
            in.transferTo(response.getOutputStream());
        } catch (java.io.IOException | IllegalStateException ignored) {
            // 浏览器拖动进度条/暂停/关闭弹窗会主动中断连接（ClientAbort/SocketTimeout），属正常播放行为，静默吞掉
        }
    }

    /** 浏览器按扩展名识别媒体容器；归档时 contentType 多为浏览器原始值，这里兜底修正。 */
    private String mediaTypeOf(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        return switch (ext) {
            case "mp4" -> "video/mp4";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "ts" -> "video/mp2t";
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            default -> contentType == null ? "application/octet-stream" : contentType;
        };
    }

    /** 下载原文件：流式输出（MinIO → 响应流直通，大文件不进内存）。 */
    @GetMapping("/{id}/download")
    public void download(@PathVariable long id, HttpServletResponse response) throws java.io.IOException {
        Dtos.LibraryFileView view = fileLibraryService.get(id);
        String encoded = URLEncoder.encode(view.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(view.contentType() == null ? "application/octet-stream" : view.contentType());
        // 显式声明总大小：否则流式写出走 chunked 传输，浏览器无法显示文件总大小与下载进度
        response.setContentLengthLong(view.fileSize());
        try (InputStream in = fileLibraryService.downloadStream(id)) {
            in.transferTo(response.getOutputStream());
        } catch (java.io.IOException | IllegalStateException ignored) {
            // 客户端中途取消下载（ClientAbort/SocketTimeout）属正常行为，静默吞掉
        }
    }
}

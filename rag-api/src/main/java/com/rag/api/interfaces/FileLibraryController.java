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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
        try (InputStream in = minioStorage.download(e.getObjectKey() + ".hls/" + rel)) {
            in.transferTo(response.getOutputStream());
        } catch (java.io.IOException ignored) {
            // hls.js 切换清晰度/暂停/关闭弹窗会主动中断连接，属正常播放行为，静默吞掉
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

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        Dtos.LibraryFileView view = fileLibraryService.get(id);
        byte[] bytes = fileLibraryService.download(id);
        String encoded = URLEncoder.encode(view.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(
                        view.contentType() == null ? "application/octet-stream" : view.contentType()))
                .body(bytes);
    }
}

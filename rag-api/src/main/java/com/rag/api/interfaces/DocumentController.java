package com.rag.api.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.application.DocumentAppService;
import com.rag.api.common.ApiResult;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentAppService documentAppService;

    @PostMapping("/api/v1/knowledge-bases/{kbId}/documents")
    public ApiResult<DocumentEntity> upload(@PathVariable long kbId,
                                            @RequestPart("file") MultipartFile file) {
        return ApiResult.ok(documentAppService.upload(kbId, file));
    }

    @GetMapping("/api/v1/knowledge-bases/{kbId}/documents")
    public ApiResult<Page<DocumentEntity>> list(@PathVariable long kbId,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size) {
        return ApiResult.ok(documentAppService.list(kbId, page, size));
    }

    @DeleteMapping("/api/v1/documents/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        documentAppService.delete(id);
        return ApiResult.ok();
    }

    @PostMapping("/api/v1/documents/{id}/start")
    public ApiResult<DocumentEntity> start(@PathVariable long id) {
        return ApiResult.ok(documentAppService.startProcessing(id));
    }

    @PostMapping("/api/v1/documents/{id}/stop")
    public ApiResult<DocumentEntity> stop(@PathVariable long id) {
        return ApiResult.ok(documentAppService.stopProcessing(id));
    }

    @PostMapping("/api/v1/documents/{id}/reparse")
    public ApiResult<DocumentEntity> reparse(@PathVariable long id) {
        return ApiResult.ok(documentAppService.reparse(id));
    }

    @GetMapping("/api/v1/documents/{id}/download-url")
    public ApiResult<String> downloadUrl(@PathVariable long id) {
        return ApiResult.ok(documentAppService.downloadUrl(id));
    }

    /**
     * 老版 .doc 预览：服务端转 HTML（保留段落/表格/粗体等基本排版），前端用沙箱 iframe 展示。
     */
    @GetMapping("/api/v1/documents/{id}/preview-html")
    public ApiResult<String> previewHtml(@PathVariable long id) {
        return ApiResult.ok(documentAppService.previewHtml(id));
    }

    /**
     * 在线预览原文件：以 inline 输出原始格式（不做文本提取或格式转换），
     * 由前端按 Content-Type 选择渲染方式，不触发浏览器下载。
     */
    @GetMapping("/api/v1/documents/{id}/preview")
    public void preview(@PathVariable long id, HttpServletResponse response) throws IOException {
        DocumentAppService.Preview p = documentAppService.preview(id);
        String encoded = URLEncoder.encode(p.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(p.contentType());
        try (InputStream in = p.in()) {
            in.transferTo(response.getOutputStream());
        } catch (IOException | IllegalStateException ignored) {
            // 客户端中途关闭预览属正常行为
        }
    }
}

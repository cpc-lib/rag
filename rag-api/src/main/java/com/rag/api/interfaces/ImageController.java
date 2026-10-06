package com.rag.api.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.application.ImageGenerationService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文生图接口：登录用户均可使用，历史仅本人可见。
 */
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageGenerationService imageGenerationService;

    @PostMapping("/generate")
    public ApiResult<ImageGenerationService.ImageView> generate(
            @RequestBody @Valid Dtos.ImageGenerateReq req) {
        return ApiResult.ok(imageGenerationService.generate(req.prompt(), req.negativePrompt(),
                req.size(), req.seed(), req.promptExtend(), req.watermark()));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        ImageGenerationService.Download d = imageGenerationService.download(id);
        String encoded = URLEncoder.encode(d.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.bytes());
    }

    @GetMapping
    public ApiResult<Page<ImageGenerationService.ImageView>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "12") long size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ApiResult.ok(imageGenerationService.page(current, size, keyword));
    }

    /** 删除本人作品（同时删除 MinIO 原图与文件库关联记录）。 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        imageGenerationService.delete(id);
        return ApiResult.ok(null);
    }
}

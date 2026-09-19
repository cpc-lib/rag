package com.rag.api.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.application.DocumentAppService;
import com.rag.api.common.ApiResult;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @GetMapping("/api/v1/documents/{id}/download-url")
    public ApiResult<String> downloadUrl(@PathVariable long id) {
        return ApiResult.ok(documentAppService.downloadUrl(id));
    }
}

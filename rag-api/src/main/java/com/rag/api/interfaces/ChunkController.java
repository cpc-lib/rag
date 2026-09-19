package com.rag.api.interfaces;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.application.ChunkAppService;
import com.rag.api.common.ApiResult;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChunkController {

    private final ChunkAppService chunkAppService;

    @GetMapping("/documents/{documentId}/chunks")
    public ApiResult<Page<ChunkEntity>> page(@PathVariable long documentId,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "50") long size) {
        return ApiResult.ok(chunkAppService.page(documentId, page, size));
    }

    @GetMapping("/chunks/{id}")
    public ApiResult<ChunkEntity> get(@PathVariable long id) {
        return ApiResult.ok(chunkAppService.get(id));
    }

    @PostMapping("/chunks")
    public ApiResult<ChunkEntity> create(@RequestBody @Valid Dtos.ChunkCreateReq req) {
        return ApiResult.ok(chunkAppService.create(req));
    }

    @PutMapping("/chunks/{id}")
    public ApiResult<ChunkEntity> update(@PathVariable long id, @RequestBody @Valid Dtos.ChunkUpdateReq req) {
        return ApiResult.ok(chunkAppService.update(id, req));
    }

    @DeleteMapping("/chunks/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        chunkAppService.delete(id);
        return ApiResult.ok();
    }
}

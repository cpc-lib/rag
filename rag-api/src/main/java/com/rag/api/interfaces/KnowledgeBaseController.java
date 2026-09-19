package com.rag.api.interfaces;

import com.rag.api.application.KnowledgeBaseService;
import com.rag.api.common.ApiResult;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    public ApiResult<KnowledgeBaseEntity> create(@RequestBody @Valid Dtos.KbCreateReq req) {
        return ApiResult.ok(knowledgeBaseService.create(req));
    }

    @GetMapping
    public ApiResult<List<KnowledgeBaseEntity>> list() {
        return ApiResult.ok(knowledgeBaseService.list());
    }

    @GetMapping("/{id}")
    public ApiResult<KnowledgeBaseEntity> get(@PathVariable long id) {
        return ApiResult.ok(knowledgeBaseService.getOwned(id));
    }

    @PutMapping("/{id}")
    public ApiResult<KnowledgeBaseEntity> update(@PathVariable long id,
                                                 @RequestBody @Valid Dtos.KbUpdateReq req) {
        return ApiResult.ok(knowledgeBaseService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        knowledgeBaseService.delete(id);
        return ApiResult.ok();
    }
}

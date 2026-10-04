package com.rag.api.interfaces;

import com.rag.api.application.ChunkUploadService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.dto.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 分片上传（大文件）：init → 逐片 PUT → complete；GET 会话支持断点续传，DELETE 中止。 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class ChunkUploadController {

    private final ChunkUploadService chunkUploadService;

    /** 初始化分片会话。 */
    @PostMapping("/init")
    public ApiResult<Dtos.UploadInitResp> init(@RequestBody @Valid Dtos.UploadInitReq req) {
        return ApiResult.ok(chunkUploadService.init(req));
    }

    /** 查询会话（断点续传：返回已传分片号）。 */
    @GetMapping("/{sessionId}")
    public ApiResult<Dtos.UploadSessionView> session(@PathVariable long sessionId) {
        return ApiResult.ok(chunkUploadService.session(sessionId));
    }

    /** 上传单个分片（原始字节流）。 */
    @PostMapping("/{sessionId}/parts/{partNumber}")
    public ApiResult<Dtos.UploadPartResp> uploadPart(@PathVariable long sessionId,
                                                     @PathVariable int partNumber,
                                                     @RequestBody byte[] body) {
        return ApiResult.ok(chunkUploadService.uploadPart(sessionId, partNumber, body));
    }

    /** 全部传完后合并分片：LIBRARY 返回文件库条目，KB_DOCUMENT 返回文档记录。 */
    @PostMapping("/{sessionId}/complete")
    public ApiResult<Object> complete(@PathVariable long sessionId) {
        return ApiResult.ok(chunkUploadService.complete(sessionId));
    }

    /** 中止会话并清理已传分片。 */
    @DeleteMapping("/{sessionId}")
    public ApiResult<Void> abort(@PathVariable long sessionId) {
        chunkUploadService.abort(sessionId);
        return ApiResult.ok(null);
    }
}

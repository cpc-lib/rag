package com.rag.api.interfaces;

import com.rag.api.application.PipelineOpsService;
import com.rag.api.common.ApiResult;
import com.rag.api.interfaces.security.AuthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 流水线运维入口（平台管理员）：队列积压观测 + DLQ 重放。
 */
@RestController
@RequiredArgsConstructor
public class PipelineOpsController {

    private final PipelineOpsService pipelineOpsService;

    @GetMapping("/api/v1/ops/pipeline/queues")
    public ApiResult<Map<String, Integer>> queueDepths() {
        AuthGuard.requirePlatform();
        return ApiResult.ok(pipelineOpsService.queueDepths());
    }

    @PostMapping("/api/v1/ops/pipeline/dlq/replay")
    public ApiResult<Integer> replayDlq(@RequestParam(defaultValue = "10") int limit) {
        AuthGuard.requirePlatform();
        return ApiResult.ok(pipelineOpsService.replayDlq(limit));
    }
}

package com.rag.api.interfaces.dto;

import com.rag.api.application.RetrievalService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.time.LocalDateTime;

/**
 * REST 请求/响应 DTO 集合（嵌套 record 组织）。
 */
public final class Dtos {

    private Dtos() {
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password, String tenantCode) {
    }

    public record LoginResp(String token, UserInfo user) {
    }

    public record UserInfo(long id, String username, String tenantId, int userType, String tenantName,
                           List<String> menuCodes) {
    }

    public record UserCreateReq(@NotBlank String username) {
    }

    public record UserView(long id, String username, int status, List<String> menuCodes,
                           List<String> toolCodes, List<Long> kbIds, List<Long> promptIds) {
    }

    public record UserCredentialResp(UserView user, String initialPassword) {
    }

    public record ResetUserPasswordResp(long userId, String initialPassword) {
    }

    public record UpdateUserReq(Integer status, List<String> menuCodes, List<String> toolCodes) {
    }

    public record GrantKbReq(List<Long> kbIds, List<Long> promptIds) {
    }

    public record ChangePasswordReq(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    /** 侧边栏菜单（后端目录下发，前端不再硬编码）。 */
    public record MenuView(String code, String name, String path, String icon) {
    }

    /** 可授权给普通用户的功能菜单 + 租户总开关状态。 */
    public record GrantableMenuView(String code, String name, boolean enabled) {
    }

    /** 功能菜单租户总开关更新：menuCodes=启用的菜单码集合（全量替换）。 */
    public record TenantMenuReq(List<String> menuCodes) {
    }

    /** 工具目录 + 租户开关 + Key 是否已配置。 */
    public record ToolCatalogView(String code, String name, String fnName, String description,
                                  boolean requiresKey, boolean enabled, boolean apiKeyConfigured) {
    }

    public record TenantCreateReq(@NotBlank String name, @NotBlank String code, String adminUsername,
                                  Integer maxStorageMb, Integer maxMqConcurrency,
                                  Long maxLlmTokensMonth, Integer maxSseConnections) {
    }

    public record TenantUpdateReq(String name, Integer status,
                                  Integer maxStorageMb, Integer maxMqConcurrency,
                                  Long maxLlmTokensMonth, Integer maxSseConnections) {
    }

    public record ResetAdminReq(String username) {
    }

    public record ResetAdminResp(long userId, String username, String initialPassword) {
    }

    /** 模型池新增/编辑请求；编辑时 apiKey 留空表示不修改。 */
    public record ModelReq(@NotBlank String name, @NotBlank String type,
                           String baseUrl, String apiKey, String model,
                           Double temperature, Double topP, Integer maxTokens,
                           Integer embeddingDim) {
    }

    /** 模型池视图。列表不回传 apiKey（为 null），详情/编辑按租户管理员请求返回明文。 */
    public record ModelView(long id, String name, String type, String baseUrl, String model,
                            boolean apiKeyConfigured, String apiKey,
                            java.math.BigDecimal temperature, java.math.BigDecimal topP,
                            Integer maxTokens, Integer embeddingDim, boolean enabled,
                            java.time.LocalDateTime createdAt) {
    }

    public record ImageGenerateReq(@NotBlank String prompt, String size, Long seed) {
    }

    public record KbCreateReq(@NotBlank String name, String description,
                              Integer parentChunkSize, Integer childChunkSize, Integer childOverlap,
                              String chunkStrategy,
                              java.util.List<String> separators) {
    }

    public record KbUpdateReq(String name, String description,
                              Integer parentChunkSize, Integer childChunkSize, Integer childOverlap,
                              String chunkStrategy,
                              java.util.List<String> separators) {
    }

    public record ChunkCreateReq(@NotNull Long documentId, @NotBlank String content, Integer page,
                                 String sectionTitle) {
    }

    public record ChunkUpdateReq(String content, Integer page, String sectionTitle) {
    }

    public record ToolConfigReq(Boolean weatherEnabled, Boolean tavilyEnabled, String tavilyApiKey) {
    }

    public record ChatStreamReq(@NotNull Long kbId, @NotBlank String question,
                                Long promptId, Long sessionId) {
    }

    public record ChatSessionView(long id, long kbId, String title,
                                  java.time.LocalDateTime createdAt,
                                  java.time.LocalDateTime updatedAt) {
    }

    /** 会话内一条消息（每行 chat_message 拆成 user/assistant 两条视图）。 */
    public record ChatMessageView(long dbId, String role, String content,
                                  java.util.List<RetrievalService.Citation> citations,
                                  Integer tokenUsage, java.time.LocalDateTime createdAt) {
    }

    public record ChatSessionDetail(Dtos.ChatSessionView session,
                                    java.util.List<Dtos.ChatMessageView> messages) {
    }

    public record PromptReq(@NotNull Long kbId, @NotBlank String name, @NotBlank String content,
                            Boolean isDefault) {
    }
}

package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.rag.api.common.BizException;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ChatMessageEntity;
import com.rag.api.infrastructure.persistence.entity.ChatSessionEntity;
import com.rag.api.infrastructure.persistence.mapper.ChatMessageMapper;
import com.rag.api.infrastructure.persistence.mapper.ChatSessionMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 问答会话管理：创建、列表、详情回看；全部历史可作为多轮上下文。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ChatSessionEntity create(String tenantId, long userId, long kbId, String title) {
        ChatSessionEntity s = new ChatSessionEntity();
        s.setTenantId(tenantId);
        s.setUserId(userId);
        s.setKbId(kbId);
        s.setTitle(title);
        sessionMapper.insert(s);
        return s;
    }

    public List<Dtos.ChatSessionView> list(String tenantId, long userId) {
        return sessionMapper.selectList(new QueryWrapper<ChatSessionEntity>()
                        .eq("tenant_id", tenantId).eq("user_id", userId).orderByDesc("updated_at"))
                .stream().map(this::toView).toList();
    }

    public Dtos.ChatSessionDetail detail(String tenantId, long userId, long id) {
        return new Dtos.ChatSessionDetail(toView(requireOwned(tenantId, userId, id)),
                messageViews(loadMessages(tenantId, id)));
    }

    /** 删除会话：校验归属（租户+用户）后级联删除其全部问答消息，跨租户访问返回不存在。 */
    @Transactional
    public void delete(String tenantId, long userId, long id) {
        requireOwned(tenantId, userId, id);
        messageMapper.delete(new QueryWrapper<ChatMessageEntity>()
                .eq("tenant_id", tenantId).eq("session_id", id));
        sessionMapper.deleteById(id);
        log.info("问答会话已删除 tenant={} user={} session={}", tenantId, userId, id);
    }

    /** 会话归属校验（租户 + 用户双重隔离）。 */
    public ChatSessionEntity requireOwned(String tenantId, long userId, long id) {
        ChatSessionEntity s = sessionMapper.selectById(id);
        if (s == null || !s.getTenantId().equals(tenantId) || s.getUserId() != userId) {
            throw BizException.notFound("会话不存在");
        }
        return s;
    }

    /** 刷新会话更新时间（插入新消息后调用，使会话按最近活跃排序）。 */
    public void touch(long sessionId) {
        sessionMapper.update(null, new UpdateWrapper<ChatSessionEntity>()
                .eq("id", sessionId).set("updated_at", LocalDateTime.now()));
    }

    /** 取全部历史，映射为 LLM 多轮消息（每轮 user + assistant）。 */
    public List<LlmClient.LlmMessage> historyMessages(String tenantId, long userId, long sessionId) {
        List<LlmClient.LlmMessage> result = new ArrayList<>();
        for (ChatMessageEntity m : loadMessages(tenantId, sessionId)) {
            result.add(new LlmClient.LlmMessage("user", m.getQuestion()));
            if (m.getAnswer() != null && !m.getAnswer().isBlank()) {
                result.add(new LlmClient.LlmMessage("assistant", m.getAnswer()));
            }
        }
        return result;
    }

    private List<ChatMessageEntity> loadMessages(String tenantId, long sessionId) {
        return messageMapper.selectList(new QueryWrapper<ChatMessageEntity>()
                .eq("tenant_id", tenantId).eq("session_id", sessionId)
                .orderByAsc("id"));
    }

    private List<Dtos.ChatMessageView> messageViews(List<ChatMessageEntity> rows) {
        List<Dtos.ChatMessageView> views = new ArrayList<>(rows.size() * 2);
        for (ChatMessageEntity m : rows) {
            views.add(new Dtos.ChatMessageView(m.getId(), "user", m.getQuestion(),
                    null, null, m.getCreatedAt()));
            if (m.getAnswer() != null && !m.getAnswer().isBlank()) {
                views.add(new Dtos.ChatMessageView(m.getId(), "assistant", m.getAnswer(),
                        parseCitations(m.getCitations()), m.getTokenUsage(), m.getCreatedAt()));
            }
        }
        return views;
    }

    private List<RetrievalService.Citation> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<RetrievalService.Citation>>() {
                    });
        } catch (Exception e) {
            log.warn("引用数据解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Dtos.ChatSessionView toView(ChatSessionEntity s) {
        return new Dtos.ChatSessionView(s.getId(), s.getKbId(), s.getTitle(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}

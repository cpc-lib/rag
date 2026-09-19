package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.api.infrastructure.persistence.mapper.ModelMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 模型池：每行一个模型（CHAT/VISION/EMBEDDING/IMAGE），每类最多一条 enabled。
 * 管理接口返回脱敏视图；运行时 requireEnabled 返回含密钥的完整实体。
 */
@Service
@RequiredArgsConstructor
public class ModelService {

    public static final String CHAT = "CHAT";
    public static final String VISION = "VISION";
    public static final String EMBEDDING = "EMBEDDING";
    public static final String IMAGE = "IMAGE";
    private static final Set<String> TYPES = Set.of(CHAT, VISION, EMBEDDING, IMAGE);

    private final ModelMapper mapper;

    // ---------- 运行时（内部调用） ----------

    public ModelEntity requireEnabled(String tenantId, String type) {
        ModelEntity m = findEnabled(tenantId, type);
        if (m == null) {
            throw BizException.badRequest(
                    "尚未启用" + label(type) + "模型，请先在【模型参数】中配置并启用");
        }
        return m;
    }

    public ModelEntity findEnabled(String tenantId, String type) {
        return mapper.selectOne(new QueryWrapper<ModelEntity>()
                .eq("tenant_id", tenantId).eq("type", type).eq("enabled", 1));
    }

    // ---------- 管理（前端，脱敏） ----------

    public Page<Dtos.ModelView> page(long current, long size, String type) {
        QueryWrapper<ModelEntity> qw = new QueryWrapper<ModelEntity>()
                .eq("tenant_id", TenantContext.require().tenantId())
                .orderByAsc("type").orderByDesc("enabled").orderByDesc("id");
        if (type != null && !type.isBlank()) {
            qw.eq("type", type);
        }
        Page<ModelEntity> p = mapper.selectPage(new Page<>(current, size), qw);
        Page<Dtos.ModelView> vp = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        // 列表不回传密钥明文
        vp.setRecords(p.getRecords().stream().map(m -> toView(m, null)).toList());
        return vp;
    }

    public Dtos.ModelView getView(long id) {
        return toView(getOwned(id));
    }

    @Transactional
    public Dtos.ModelView create(Dtos.ModelReq req) {
        String type = normalizeType(req.type());
        ModelEntity m = new ModelEntity();
        m.setTenantId(TenantContext.require().tenantId());
        m.setType(type);
        applyFields(m, req, true);
        m.setEnabled(false);
        mapper.insert(m);
        return toView(m);
    }

    @Transactional
    public Dtos.ModelView update(long id, Dtos.ModelReq req) {
        ModelEntity m = getOwned(id);
        applyFields(m, req, false);
        mapper.updateById(m);
        return toView(m);
    }

    /** 启用：同类仅保留一条，自动顶替旧的启用行，不影响其他类型。 */
    @Transactional
    public Dtos.ModelView enable(long id) {
        ModelEntity m = getOwned(id);
        ModelEntity current = findEnabled(m.getTenantId(), m.getType());
        if (current != null && !current.getId().equals(m.getId())) {
            current.setEnabled(false);
            mapper.updateById(current);
        }
        m.setEnabled(true);
        mapper.updateById(m);
        return toView(m);
    }

    @Transactional
    public Dtos.ModelView disable(long id) {
        ModelEntity m = getOwned(id);
        m.setEnabled(false);
        mapper.updateById(m);
        return toView(m);
    }

    // ---------- 私有 ----------

    private void applyFields(ModelEntity m, Dtos.ModelReq req, boolean isCreate) {
        m.setName(req.name().trim());
        if (req.baseUrl() != null) {
            m.setBaseUrl(req.baseUrl().isBlank() ? null : req.baseUrl().trim());
        }
        if (isCreate) {
            m.setApiKey(req.apiKey() == null || req.apiKey().isBlank() ? null : req.apiKey());
        } else if (req.apiKey() != null && !req.apiKey().isBlank()) {
            m.setApiKey(req.apiKey());
        }
        if (req.model() != null) {
            m.setModel(req.model().isBlank() ? null : req.model().trim());
        }
        if (CHAT.equals(m.getType())) {
            if (req.temperature() != null) {
                if (req.temperature() < 0 || req.temperature() > 2) {
                    throw BizException.badRequest("temperature 取值范围 0~2");
                }
                m.setTemperature(BigDecimal.valueOf(req.temperature()));
            } else if (isCreate) {
                m.setTemperature(new BigDecimal("0.70"));
            }
            if (req.topP() != null) {
                if (req.topP() < 0 || req.topP() > 1) {
                    throw BizException.badRequest("top_p 取值范围 0~1");
                }
                m.setTopP(BigDecimal.valueOf(req.topP()));
            } else if (isCreate) {
                m.setTopP(new BigDecimal("0.90"));
            }
            if (req.maxTokens() != null) {
                if (req.maxTokens() < 1 || req.maxTokens() > 128000) {
                    throw BizException.badRequest("max_tokens 取值 1~128000");
                }
                m.setMaxTokens(req.maxTokens());
            } else if (isCreate) {
                m.setMaxTokens(2048);
            }
        }
        if (EMBEDDING.equals(m.getType())) {
            if (req.embeddingDim() != null) {
                if (req.embeddingDim() < 64 || req.embeddingDim() > 65536) {
                    throw BizException.badRequest("embedding_dim 取值 64~65536");
                }
                m.setEmbeddingDim(req.embeddingDim());
            } else if (isCreate) {
                m.setEmbeddingDim(1024);
            }
        }
    }

    private ModelEntity getOwned(long id) {
        ModelEntity m = mapper.selectById(id);
        if (m == null || !m.getTenantId().equals(TenantContext.require().tenantId())) {
            throw BizException.notFound("模型不存在");
        }
        return m;
    }

    private Dtos.ModelView toView(ModelEntity m) {
        return toView(m, m.getApiKey());
    }

    private Dtos.ModelView toView(ModelEntity m, String apiKey) {
        return new Dtos.ModelView(m.getId(), m.getName(), m.getType(), m.getBaseUrl(), m.getModel(),
                m.getApiKey() != null && !m.getApiKey().isBlank(), apiKey,
                m.getTemperature(), m.getTopP(), m.getMaxTokens(), m.getEmbeddingDim(),
                Boolean.TRUE.equals(m.getEnabled()), m.getCreatedAt());
    }

    private String normalizeType(String t) {
        String type = t == null ? "" : t.trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw BizException.badRequest("type 必须是 CHAT / VISION / EMBEDDING / IMAGE");
        }
        return type;
    }

    private String label(String type) {
        return switch (type) {
            case CHAT -> "对话";
            case VISION -> "视觉";
            case EMBEDDING -> "向量";
            case IMAGE -> "文生图";
            default -> type;
        };
    }
}

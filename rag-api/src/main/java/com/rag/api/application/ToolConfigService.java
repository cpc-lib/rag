package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.infrastructure.etcd.EtcdService;
import com.rag.api.infrastructure.persistence.entity.SysToolEntity;
import com.rag.api.infrastructure.persistence.entity.ToolConfigEntity;
import com.rag.api.infrastructure.persistence.mapper.SysToolMapper;
import com.rag.api.infrastructure.persistence.mapper.ToolConfigMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具启停（spec 3.2 / 4.3），变更同步 etcd 供动态感知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolConfigService {

    private final ToolConfigMapper mapper;
    private final SysToolMapper sysToolMapper;
    private final EtcdService etcdService;
    private final ObjectMapper objectMapper;

    public ToolConfigEntity getByTenant(String tenantId) {
        ToolConfigEntity cfg = mapper.selectOne(new QueryWrapper<ToolConfigEntity>().eq("tenant_id", tenantId));
        if (cfg == null) {
            cfg = new ToolConfigEntity();
            cfg.setTenantId(tenantId);
            cfg.setWeatherEnabled(false);
            cfg.setTavilyEnabled(false);
            mapper.insert(cfg);
        }
        return cfg;
    }

    public ToolConfigEntity update(String tenantId, Dtos.ToolConfigReq req) {
        ToolConfigEntity cfg = getByTenant(tenantId);
        if (req.weatherEnabled() != null) {
            cfg.setWeatherEnabled(req.weatherEnabled());
        }
        if (req.tavilyEnabled() != null) {
            cfg.setTavilyEnabled(req.tavilyEnabled());
        }
        if (req.tavilyApiKey() != null) {
            cfg.setTavilyApiKey(req.tavilyApiKey().isBlank() ? null : req.tavilyApiKey());
        }
        if (Boolean.TRUE.equals(cfg.getTavilyEnabled()) && (cfg.getTavilyApiKey() == null || cfg.getTavilyApiKey().isBlank())) {
            throw BizException.badRequest("启用 Tavily 前请先配置 tavilyApiKey");
        }
        mapper.updateById(cfg);
        publishToEtcd(cfg);
        return cfg;
    }

    public Map mask(String tenantId) {
        ToolConfigEntity cfg = getByTenant(tenantId);
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("weatherEnabled", cfg.getWeatherEnabled());
        map.put("tavilyEnabled", cfg.getTavilyEnabled());
        map.put("tavilyApiKey", cfg.getTavilyApiKey());
        map.put("tavilyApiKeyConfigured", cfg.getTavilyApiKey() != null && !cfg.getTavilyApiKey().isBlank());
        return map;
    }

    /** 工具目录（后端 sys_tool）叠加租户开关与 Key 状态。 */
    public List<Dtos.ToolCatalogView> catalog(String tenantId) {
        ToolConfigEntity cfg = getByTenant(tenantId);
        boolean keyConfigured = cfg.getTavilyApiKey() != null && !cfg.getTavilyApiKey().isBlank();
        return sysToolMapper.selectList(new QueryWrapper<SysToolEntity>()
                        .eq("status", 1).orderByAsc("sort")).stream()
                .map(t -> new Dtos.ToolCatalogView(t.getCode(), t.getName(), t.getFnName(),
                        t.getDescription(), Boolean.TRUE.equals(t.getRequiresKey()),
                        tenantSwitchOn(t.getCode(), cfg),
                        "tavily".equals(t.getCode()) && keyConfigured))
                .toList();
    }

    /** tool_config 中各工具的租户总开关（列与工具码一一对应）。 */
    private boolean tenantSwitchOn(String code, ToolConfigEntity cfg) {
        return switch (code) {
            case "weather" -> Boolean.TRUE.equals(cfg.getWeatherEnabled());
            case "tavily" -> Boolean.TRUE.equals(cfg.getTavilyEnabled());
            default -> false;
        };
    }

    private void publishToEtcd(ToolConfigEntity cfg) {
        try {
            etcdService.putTenantToolConfig(cfg.getTenantId(), objectMapper.writeValueAsString(Map.of(
                    "weatherEnabled", cfg.getWeatherEnabled(),
                    "tavilyEnabled", cfg.getTavilyEnabled())));
        } catch (Exception e) {
            log.warn("工具配置写入 etcd 失败（不影响本地生效）: {}", e.getMessage());
        }
    }
}

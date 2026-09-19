package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.api.common.BizException;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import com.rag.api.infrastructure.persistence.entity.UserPromptEntity;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.persistence.mapper.PromptTemplateMapper;
import com.rag.api.infrastructure.persistence.mapper.UserPromptMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 提示词模板管理：模板按知识库归属，每个知识库有自己的模板集合与一个默认模板。
 * 占位符：{{资料}} 知识库召回、{{外部信息}} 工具/联网结果、{{问题}} 用户问题。
 */
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    public static final String DEFAULT_TEMPLATE_NAME = "默认知识库问答模板";

    public static final String DEFAULT_TEMPLATE_CONTENT = """
            你是企业知识库智能问答助手。请优先依据【资料】回答用户问题；引用资料时在句末标注角标序号，如 [1]。\
            【外部信息】仅作补充参考。若资料与问题无关且无外部信息，请如实说明未找到相关内容。

            【资料】
            {{资料}}
            {{外部信息}}""";

    private final PromptTemplateMapper mapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserPromptMapper userPromptMapper;

    /** 租户下全部模板（管理页/授权弹窗用）。 */
    public List<PromptTemplateEntity> list(String tenantId) {
        return mapper.selectList(new QueryWrapper<PromptTemplateEntity>()
                .eq("tenant_id", tenantId)
                .orderByDesc("is_default")
                .orderByDesc("updated_at"));
    }

    /** 指定知识库下的模板。 */
    public List<PromptTemplateEntity> listByKb(long kbId) {
        return mapper.selectList(new QueryWrapper<PromptTemplateEntity>()
                .eq("kb_id", kbId)
                .orderByDesc("is_default")
                .orderByDesc("updated_at"));
    }

    public PromptTemplateEntity get(String tenantId, long id) {
        PromptTemplateEntity t = mapper.selectById(id);
        if (t == null || !t.getTenantId().equals(tenantId)) {
            throw BizException.notFound("提示词模板不存在");
        }
        return t;
    }

    public PromptTemplateEntity getDefault(String tenantId, long kbId) {
        PromptTemplateEntity t = mapper.selectOne(new QueryWrapper<PromptTemplateEntity>()
                .eq("tenant_id", tenantId)
                .eq("kb_id", kbId)
                .eq("is_default", true)
                .last("LIMIT 1"));
        if (t == null) {
            throw BizException.notFound("该知识库未配置默认提示词模板");
        }
        return t;
    }

    @Transactional
    public PromptTemplateEntity create(String tenantId, Dtos.PromptReq req) {
        requireKb(tenantId, req.kbId());
        PromptTemplateEntity t = new PromptTemplateEntity();
        t.setTenantId(tenantId);
        t.setKbId(req.kbId());
        t.setName(req.name());
        t.setContent(req.content());
        t.setIsDefault(false);
        mapper.insert(t);
        if (Boolean.TRUE.equals(req.isDefault())) {
            markDefault(tenantId, t.getId());
        }
        return get(tenantId, t.getId());
    }

    @Transactional
    public PromptTemplateEntity update(String tenantId, long id, Dtos.PromptReq req) {
        PromptTemplateEntity t = get(tenantId, id);
        t.setName(req.name());
        t.setContent(req.content());
        mapper.updateById(t);
        if (Boolean.TRUE.equals(req.isDefault())) {
            markDefault(tenantId, id);
        }
        return get(tenantId, id);
    }

    @Transactional
    public void delete(String tenantId, long id) {
        PromptTemplateEntity t = get(tenantId, id);
        if (Boolean.TRUE.equals(t.getIsDefault())) {
            throw BizException.badRequest("默认模板不可删除，可先将其他模板设为默认");
        }
        userPromptMapper.delete(new QueryWrapper<UserPromptEntity>().eq("prompt_id", id));
        mapper.deleteById(id);
    }

    /** 删除指定知识库下全部模板（知识库删除时用），同时清理用户提示词授权。 */
    @Transactional
    public void deleteByKb(long kbId) {
        userPromptMapper.delete(new QueryWrapper<UserPromptEntity>()
                .inSql("prompt_id", "SELECT id FROM prompt_template WHERE kb_id = " + kbId));
        mapper.delete(new QueryWrapper<PromptTemplateEntity>().eq("kb_id", kbId));
    }

    /** 新建知识库时播种其默认模板。 */
    @Transactional
    public void seedDefault(String tenantId, long kbId) {
        PromptTemplateEntity t = new PromptTemplateEntity();
        t.setTenantId(tenantId);
        t.setKbId(kbId);
        t.setName(DEFAULT_TEMPLATE_NAME);
        t.setContent(DEFAULT_TEMPLATE_CONTENT);
        t.setIsDefault(true);
        mapper.insert(t);
    }

    /** 渲染模板：替换占位符。 */
    public String render(PromptTemplateEntity t, String material, String external, String question) {
        String externalBlock = external == null || external.isBlank()
                ? "" : "\n【外部信息】\n" + external;
        return t.getContent()
                .replace("{{资料}}", material)
                .replace("{{外部信息}}", externalBlock)
                .replace("{{问题}}", question == null ? "" : question);
    }

    private void requireKb(String tenantId, Long kbId) {
        if (kbId == null) {
            throw BizException.badRequest("请选择所属知识库");
        }
        KnowledgeBaseEntity kb = kbMapper.selectById(kbId);
        if (kb == null || !kb.getTenantId().equals(tenantId)) {
            throw BizException.badRequest("所属知识库不存在");
        }
    }

    /** 默认模板仅在同一知识库内唯一。 */
    private void markDefault(String tenantId, long id) {
        PromptTemplateEntity t = get(tenantId, id);
        List<PromptTemplateEntity> olds = mapper.selectList(new QueryWrapper<PromptTemplateEntity>()
                .eq("kb_id", t.getKbId())
                .eq("is_default", true));
        for (PromptTemplateEntity old : olds) {
            if (!old.getId().equals(id)) {
                old.setIsDefault(false);
                mapper.updateById(old);
            }
        }
        t.setIsDefault(true);
        mapper.updateById(t);
    }
}

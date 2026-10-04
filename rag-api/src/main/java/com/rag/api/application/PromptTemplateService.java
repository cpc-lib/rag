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
 * 提示词模板管理：
 * - 知识库问答模板：按知识库归属（kb_id 非空，category 为 NULL），每个知识库有默认模板。
 * - 字幕翻译模板：租户级全局模板（kb_id 为 NULL，category='SUBTITLE'），每租户一条，懒加载默认。
 * 占位符：知识库问答 {{资料}} {{外部信息}} {{问题}}；字幕翻译 {{目标语言}}。
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

    /** 字幕翻译模板分类标识。 */
    public static final String CATEGORY_SUBTITLE = "SUBTITLE";

    public static final String DEFAULT_SUBTITLE_NAME = "字幕翻译模板";

    public static final String DEFAULT_SUBTITLE_CONTENT = """
            你是专业字幕翻译师。严格遵守：
            1. 逐条独立翻译，绝不合并或拆分句子，返回条数必须与输入完全一致。
            2. 只翻译文本内容，不改动任何序号、时间轴或格式标记。
            3. 保持口语自然流畅，不改变原意。
            4. 返回格式：纯 JSON 字符串数组，数组长度等于待翻译条数，顺序与输入一致，不要任何额外说明。
            目标语言：{{目标语言}}。""";

    private final PromptTemplateMapper mapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserPromptMapper userPromptMapper;

    /** 租户下全部知识库问答模板（管理页/授权弹窗用），排除字幕等非知识库模板。 */
    public List<PromptTemplateEntity> list(String tenantId) {
        return mapper.selectList(new QueryWrapper<PromptTemplateEntity>()
                .eq("tenant_id", tenantId)
                .isNull("category")
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

    // ---------------- 字幕翻译模板 ----------------

    /** 获取租户的字幕翻译模板；不存在则播种默认模板。 */
    @Transactional
    public PromptTemplateEntity getSubtitleTemplate(String tenantId) {
        PromptTemplateEntity t = mapper.selectOne(new QueryWrapper<PromptTemplateEntity>()
                .eq("tenant_id", tenantId)
                .eq("category", CATEGORY_SUBTITLE)
                .last("LIMIT 1"));
        if (t == null) {
            t = new PromptTemplateEntity();
            t.setTenantId(tenantId);
            t.setKbId(null);
            t.setCategory(CATEGORY_SUBTITLE);
            t.setName(DEFAULT_SUBTITLE_NAME);
            t.setContent(DEFAULT_SUBTITLE_CONTENT);
            t.setIsDefault(true);
            mapper.insert(t);
        }
        return t;
    }

    /** 更新租户的字幕翻译模板内容。 */
    @Transactional
    public PromptTemplateEntity updateSubtitleTemplate(String tenantId, String content) {
        if (content == null || content.isBlank()) {
            throw BizException.badRequest("提示词内容不能为空");
        }
        PromptTemplateEntity t = getSubtitleTemplate(tenantId);
        t.setContent(content);
        mapper.updateById(t);
        return t;
    }

    /** 渲染字幕翻译模板：替换 {{目标语言}} 占位符。 */
    public String renderSubtitle(PromptTemplateEntity t, String targetLang) {
        return t.getContent().replace("{{目标语言}}", targetLang == null ? "" : targetLang);
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

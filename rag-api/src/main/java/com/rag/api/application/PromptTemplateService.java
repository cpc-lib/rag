package com.rag.api.application;

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

    /**
     * 字幕翻译模板分类标识。
     */
    public static final String CATEGORY_SUBTITLE = "SUBTITLE";

    public static final String DEFAULT_SUBTITLE_NAME = "字幕翻译模板";

    public static final String DEFAULT_SUBTITLE_CONTENT = """
            你是专业字幕翻译师。请将输入的字幕逐条翻译为{{目标语言}}，并严格遵守以下契约。
            
            【翻译规则】
            
            1. 严格逐条对应

            输入中的每一条字幕，必须且只能对应输出 JSON 数组中的一个对象元素。

            输入 N 条字幕，输出必须恰好包含 N 个对象元素。

            不得遗漏任何条目。
            不得新增任何条目。
            不得合并相邻字幕。
            不得将一条字幕拆分成多个元素。

            即使某条字幕内部包含多句话、省略号、感叹词、语气词或换行，也只能对应一个数组元素。
            
            2. 只翻译字幕正文
            
            仅翻译实际的文字内容。
            
            保持原意准确。
            
            译文应自然、流畅，符合目标语言的口语表达习惯。
            
            不得擅自增加原文不存在的信息。
            
            不得删减具有实际语义的信息。
            
            3. 保持格式信息不变
            
            字幕序号、时间轴、HTML/XML 标签、ASS/SSA 标签、特殊格式标记以及其他非正文格式信息，不得翻译、修改、删除或重新生成。
            
            4. 上下文处理
            
            标注为“上下文”的内容仅用于理解人物关系、语气、前后剧情和语义。
            
            “上下文”内容禁止翻译。
            
            “上下文”内容禁止出现在最终输出中。
            
            上下文不计入需要输出的字幕条目数量。
            
            5. 空字幕处理

            如果某条字幕正文为空，则对应对象的 t 字段输出空字符串：

            {"n":序号,"t":""}

            【输出格式】

            1. 最终结果只能输出一个合法的 JSON 数组，数组元素为对象。

            2. 除 JSON 数组之外，禁止输出任何其他内容，包括但不限于：

            解释
            提示语
            标题
            注释
            Markdown
            代码围栏
            “翻译结果如下”等额外文字

            3. 每个元素必须是形如 {"n":序号,"t":"译文"} 的 JSON 对象：

            n 为该条字幕的输入序号（从 1 开始），仅用于机器核对，不是译文内容。

            t 为该条字幕的译文，必须是 JSON 字符串。

            4. 输入 N 条字幕时：

            输出数组长度必须严格等于 N。

            n 必须恰好覆盖 1 到 N，不重复、不遗漏。

            n 与 t 必须一一对应，顺序不得改变。

            5. JSON 转义规则：

            t 字符串内部出现双引号 " 时，必须转义为 \\"。

            t 字符串内部需要保留换行时，必须转义为 \\n。

            空内容必须输出 "t": ""。
            
            【严格禁止】
            
            禁止合并两条或多条字幕。
            
            禁止将一条字幕拆成多个数组元素。
            
            禁止遗漏字幕。
            
            禁止重复字幕。
            
            禁止调整字幕顺序。
            
            禁止输出上下文。
            
            禁止翻译上下文。
            
            禁止输出原文和译文对照。

            禁止在 t 译文中输出字幕序号或条目编号（n 字段除外）。

            禁止输出时间轴。
            
            禁止添加解释、备注或翻译说明。
            
            禁止使用 Markdown 代码块包裹 JSON。
            
            禁止在 JSON 数组前后添加任何文字。
            
            【示例】
            
            输入：
            
            1. Hey! Wait for me.
            2. I'm fine. And you?
            
            输出：

            [{"n":1,"t":"嘿！等等我。"},{"n":2,"t":"我很好，你呢？"}]

            注意：

            第二条字幕即使包含多句话，也只能对应 JSON 数组中的一个对象元素。
            
            【输出前检查】
            
            输出前必须自行检查以下内容：
            
            1. 输入字幕条目数与输出数组元素数完全一致。
            
            2. 每条字幕均严格一一对应。
            
            3. 没有合并任何字幕。
            
            4. 没有拆分任何字幕。
            
            5. 没有遗漏或重复字幕。
            
            6. 没有输出“上下文”内容。
            
            7. 没有输出任何额外说明。
            
            8. 输出内容必须是合法 JSON。
            
            9. 最终回复必须以 [ 开始，以 ] 结束。
            """;

    private final PromptTemplateMapper mapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserPromptMapper userPromptMapper;

    /**
     * 租户下全部知识库问答模板（管理页/授权弹窗用），排除字幕等非知识库模板。
     */
    public List<PromptTemplateEntity> list(String tenantId) {
        return mapper.selectByTenantIdAndCategoryIsNull(tenantId);
    }

    /**
     * 指定知识库下的模板。
     */
    public List<PromptTemplateEntity> listByKb(long kbId) {
        return mapper.selectByKbId(kbId);
    }

    public PromptTemplateEntity get(String tenantId, long id) {
        PromptTemplateEntity t = mapper.selectById(id);
        if (t == null || !t.getTenantId().equals(tenantId)) {
            throw BizException.notFound("提示词模板不存在");
        }
        return t;
    }

    public PromptTemplateEntity getDefault(String tenantId, long kbId) {
        PromptTemplateEntity t = mapper.selectDefaultByTenantIdAndKbId(tenantId, kbId);
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
        userPromptMapper.deleteByPromptId(id);
        mapper.deleteById(id);
    }

    /**
     * 删除指定知识库下全部模板（知识库删除时用），同时清理用户提示词授权。
     */
    @Transactional
    public void deleteByKb(long kbId) {
        userPromptMapper.deleteByKbId(kbId);
        mapper.logicDeleteByKbId(kbId);
    }

    /**
     * 新建知识库时播种其默认模板。
     */
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

    /**
     * 获取租户的字幕翻译模板；不存在则播种默认模板。
     */
    @Transactional
    public PromptTemplateEntity getSubtitleTemplate(String tenantId) {
        PromptTemplateEntity t = mapper.selectByTenantIdAndCategory(tenantId, CATEGORY_SUBTITLE);
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

    /**
     * 更新租户的字幕翻译模板内容。
     */
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

    /**
     * 渲染字幕翻译模板：替换 {{目标语言}} 占位符。
     */
    public String renderSubtitle(PromptTemplateEntity t, String targetLang) {
        return t.getContent().replace("{{目标语言}}", targetLang == null ? "" : targetLang);
    }

    /**
     * 渲染模板：替换占位符。
     */
    public String render(PromptTemplateEntity t, String material, String external, String question) {
        String externalBlock = external == null || external.isBlank() ? "" : "\n【外部信息】\n" + external;
        return t.getContent().replace("{{资料}}", material).replace("{{外部信息}}", externalBlock).replace("{{问题}}", question == null ? "" : question);
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

    /**
     * 默认模板仅在同一知识库内唯一。
     */
    private void markDefault(String tenantId, long id) {
        PromptTemplateEntity t = get(tenantId, id);
        List<PromptTemplateEntity> olds = mapper.selectByKbIdAndIsDefault(t.getKbId(), true);
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

package com.rag.api.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.llm.LlmClient;
import com.rag.api.infrastructure.persistence.entity.ModelEntity;
import com.rag.api.infrastructure.persistence.entity.SubtitleCueEntity;
import com.rag.api.infrastructure.persistence.entity.SubtitleCueBackupEntity;
import com.rag.api.infrastructure.persistence.entity.SubtitleEntity;
import com.rag.api.infrastructure.persistence.entity.TranslateLangEntity;
import com.rag.api.infrastructure.persistence.mapper.SubtitleCueMapper;
import com.rag.api.infrastructure.persistence.mapper.SubtitleCueBackupMapper;
import com.rag.api.infrastructure.persistence.mapper.SubtitleMapper;
import com.rag.api.infrastructure.persistence.mapper.TranslateLangMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 字幕转换编排：VTT/SRT/ASS 上传（先做格式规范校验）→ 逐条解析入详情表（subtitle_cue）→ LLM 翻译（回填译文列）→ 编辑 → 下载。
 * <p>
 * 时间轴全程不可改，原文与译文分列存储；下载时优先取译文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubtitleService {

    private static final int BATCH_SIZE = 25;
    private static final int CONTEXT_RADIUS = 5;
    /** 条数不符二分重译的最小批大小：不大于该值的批次仍失败则逐条兜底。 */
    private static final int SPLIT_MIN = 8;

    private final SubtitleMapper subtitleMapper;
    private final SubtitleCueMapper cueMapper;
    private final SubtitleCueBackupMapper cueBackupMapper;
    private final TranslateLangMapper langMapper;
    private final ModelService modelService;
    private final LlmClient llmClient;
    private final UserManageService userManageService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final FileLibraryService fileLibraryService;

    // ---------------- 上传 ----------------

    public Dtos.SubtitleView upload(MultipartFile file, String sha256) {
        TenantContext.Session s = TenantContext.require();
        userManageService.requireMenu("subtitle");
        // 秒传：前端已算 SHA-256，同租户同 hash 的原始上传已存在则直接复用
        if (sha256 != null && !sha256.isBlank()) {
            Long existingId = fileLibraryService.findSubtitleIdBySha256(s.tenantId(), sha256);
            if (existingId != null) {
                // 防御：字幕记录可能已被逻辑删除（library_file 归档残留），此时不能复用
                try {
                    SubtitleEntity check = subtitleMapper.selectById(existingId);
                    if (check != null) {
                        log.info("字幕秒传命中 tenant={} sha={} -> subtitle={}", s.tenantId(), sha256, existingId);
                        return get(existingId);
                    }
                    log.info("秒传命中但字幕已删除，重新上传 tenant={} sha={} -> subtitle={}", s.tenantId(), sha256, existingId);
                } catch (Exception ignored) {
                    // 查询失败按未命中处理，继续正常上传流程
                }
            }
        }
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        String ext = SubtitleCodec.extOf(fileName);
        if (!Set.of("vtt", "srt", "ass").contains(ext)) {
            throw BizException.badRequest("仅支持 .vtt / .srt / .ass 字幕文件");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(com.rag.api.common.ErrorCode.INTERNAL, "读取文件失败: " + e.getMessage());
        }
        // 去除 UTF-8 BOM，避免影响 WEBVTT 头 / 序号行校验
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        String err = SubtitleCodec.validate(ext, content);
        if (err != null) {
            throw BizException.badRequest("字幕格式校验未通过: " + err);
        }
        List<SubtitleCodec.Cue> cues = SubtitleCodec.parse(ext, content);
        if (cues.isEmpty()) {
            throw BizException.badRequest("未能从字幕文件中解析出任何字幕条");
        }
        SubtitleEntity e = new SubtitleEntity();
        e.setTenantId(s.tenantId());
        e.setUserId(s.userId());
        e.setOriginalName(fileName);
        e.setSourceLang(null);
        e.setTargetLang(null);
        // 原始 SRT 全文仅作快照保留；正文以详情表为准
        e.setSrtContent(SubtitleCodec.toSrt(cues));
        subtitleMapper.insert(e);
        insertCues(s.tenantId(), e.getId(), cues, null);
        // 原始上传文件同步归档到文件库（归档失败不阻断上传主流程）
        String contentType = switch (ext) {
            case "srt" -> "application/x-subrip";
            case "ass" -> "text/plain";
            default -> "text/vtt";
        };
        try {
            fileLibraryService.archive(s.tenantId(), s.userId(), e.getId(), fileName, contentType,
                    FileLibraryService.SRC_SUBTITLE_UPLOAD, content.getBytes(StandardCharsets.UTF_8), sha256);
        } catch (Exception ex) {
            log.warn("原始文件归档失败（不影响字幕上传）: {}", ex.getMessage());
        }
        return get(e.getId());
    }

    /** 详情行落库：逐条写入 subtitle_cue，translated 为空表示未翻译；同时写一份备份行（删除字幕后供文件库归档编辑）。 */
    private void insertCues(String tenantId, long subtitleId,
                            List<SubtitleCodec.Cue> cues, List<String> translated) {
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCueEntity row = new SubtitleCueEntity();
            row.setTenantId(tenantId);
            row.setSubtitleId(subtitleId);
            row.setSeq(i + 1);
            row.setStartTime(cues.get(i).start());
            row.setEndTime(cues.get(i).end());
            row.setContent(cues.get(i).text());
            if (translated != null) {
                row.setTranslatedText(translated.get(i));
            }
            cueMapper.insert(row);

            SubtitleCueBackupEntity backup = new SubtitleCueBackupEntity();
            backup.setTenantId(tenantId);
            backup.setSubtitleId(subtitleId);
            backup.setSeq(i + 1);
            backup.setStartTime(cues.get(i).start());
            backup.setEndTime(cues.get(i).end());
            backup.setContent(cues.get(i).text());
            if (translated != null) {
                backup.setTranslatedText(translated.get(i));
            }
            cueBackupMapper.insert(backup);
        }
    }

    // ---------------- 详情 / 列表 ----------------

    public Dtos.SubtitleView get(long id) {
        SubtitleEntity e = requireOwned(id);
        return toView(e, requireCues(e));
    }

    public List<Dtos.SubtitleListItem> list() {
        TenantContext.Session s = TenantContext.require();
        List<SubtitleEntity> rows = subtitleMapper.selectListByTenantUser(s.tenantId(), s.userId());
        return rows.stream().map(this::toListItem).toList();
    }

    /**
     * 读取详情行（按序号升序）；旧记录详情表为空时从 srt_content 懒迁移。
     */
    private List<SubtitleCueEntity> requireCues(SubtitleEntity e) {
        List<SubtitleCueEntity> rows = cueMapper.selectListBySubtitleId(e.getId());
        if (!rows.isEmpty()) {
            return rows;
        }
        List<SubtitleCodec.Cue> cues = SubtitleCodec.parseSrt(e.getSrtContent());
        if (cues.isEmpty()) {
            return List.of();
        }
        log.info("字幕记录 {} 无详情行，从 srt_content 迁移 {} 条", e.getId(), cues.size());
        insertCues(e.getTenantId(), e.getId(), cues, null);
        return cueMapper.selectListBySubtitleId(e.getId());
    }

    // ---------------- 翻译 ----------------

    @Transactional
    public Dtos.SubtitleView translate(long id, Dtos.SubtitleTranslateReq req) {
        userManageService.requireMenu("subtitle");
        TenantContext.Session s = TenantContext.require();
        SubtitleEntity e = requireOwned(id);
        String lang = req.targetLang() == null ? "" : req.targetLang().trim();
        // 目标语言必须是租户维护列表中的语言
        if (lang.isEmpty() || langMapper.countByTenantIdAndName(s.tenantId(), lang) == 0) {
            throw BizException.badRequest("目标语言不存在，请先在语言维护窗口中添加");
        }
        List<SubtitleCueEntity> rows = requireCues(e);
        if (rows.isEmpty()) {
            throw BizException.badRequest("字幕内容为空，无法翻译");
        }
        // 勾选序号（1 起与页面一致）转 0 下标；为空 = 全部
        List<Integer> selected = normalizeIndices(req.indices(), rows.size());

        ModelEntity m = modelService.requireEnabled(s.tenantId(), ModelService.CHAT);
        if (m.getBaseUrl() == null || m.getModel() == null) {
            throw BizException.badRequest("启用的对话模型缺少 Base URL 或模型名称");
        }

        // 只翻译勾选条目，其余保留不动
        List<String> targets = new ArrayList<>(selected.size());
        for (int pos : selected) {
            targets.add(rows.get(pos).getContent());
        }
        List<String> translated = translateCues(targets, m, lang);

        for (int k = 0; k < selected.size(); k++) {
            SubtitleCueEntity row = rows.get(selected.get(k));
            String translatedText = translated.get(k);
            row.setTranslatedText(translatedText);
            cueMapper.updateById(row);
            // 同步备份表，保证删除字幕后文件库归档仍能还原最新译文
            cueBackupMapper.updateTranslatedTextBySubtitleIdAndSeq(e.getId(), row.getSeq(), translatedText);
        }
        e.setTargetLang(lang);
        subtitleMapper.updateById(e);
        return toView(e, rows);
    }

    // ---------------- 翻译语言维护 ----------------

    /** 语言列表（租户级）；默认两项由 Flyway 种子数据负责，删光即为空不再懒播种。 */
    public List<Dtos.TranslateLangView> listLangs() {
        userManageService.requireMenu("subtitle");
        String tenantId = TenantContext.require().tenantId();
        return langMapper.selectListByTenantId(tenantId)
                .stream().map(r -> new Dtos.TranslateLangView(r.getId(), r.getName())).toList();
    }

    /** 新增语言：重名校验，追加到列表尾部；返回更新后的完整列表。 */
    public List<Dtos.TranslateLangView> addLang(String name) {
        userManageService.requireMenu("subtitle");
        String tenantId = TenantContext.require().tenantId();
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 50) {
            throw BizException.badRequest("语言名称需为 1~50 个字符");
        }
        if (langMapper.countByTenantIdAndName(tenantId, n) > 0) {
            throw BizException.badRequest("语言已存在: " + n);
        }
        TranslateLangEntity row = new TranslateLangEntity();
        row.setTenantId(tenantId);
        row.setName(n);
        row.setSortNo(langMapper.countByTenantId(tenantId).intValue() + 1);
        langMapper.insert(row);
        return listLangs();
    }

    /** 删除语言。 */
    public void deleteLang(long id) {
        userManageService.requireMenu("subtitle");
        String tenantId = TenantContext.require().tenantId();
        TranslateLangEntity row = langMapper.selectById(id);
        if (row == null || !row.getTenantId().equals(tenantId)) {
            throw BizException.notFound("语言不存在");
        }
        langMapper.logicDeleteById(id);
    }

    /** 勾选序号校验与规范化：1 起转 0 下标，去重升序；空/null = 全部。 */
    private List<Integer> normalizeIndices(List<Integer> indices, int size) {
        if (indices == null || indices.isEmpty()) {
            List<Integer> all = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                all.add(i);
            }
            return all;
        }
        List<Integer> sorted = new ArrayList<>(indices.stream().distinct().toList());
        for (Integer idx : sorted) {
            if (idx == null || idx < 1 || idx > size) {
                throw BizException.badRequest("序号超出范围: " + idx + "（有效范围 1-" + size + "）");
            }
        }
        sorted.sort(Integer::compareTo);
        return sorted.stream().map(i -> i - 1).toList();
    }

    /** 分批翻译：每批 BATCH_SIZE 条，带 ±CONTEXT_RADIUS 上下文，返回与原文等长的译文列表。 */
    private List<String> translateCues(List<String> texts, ModelEntity m, String targetLabel) {
        List<String> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, texts.size());
            List<String> batchTexts = texts.subList(from, to);
            // 上下文（不参与输出）
            List<String> before = new ArrayList<>();
            for (int i = Math.max(0, from - CONTEXT_RADIUS); i < from; i++) {
                before.add(texts.get(i));
            }
            List<String> after = new ArrayList<>();
            for (int i = to; i < Math.min(texts.size(), to + CONTEXT_RADIUS); i++) {
                after.add(texts.get(i));
            }
            result.addAll(translateBatch(batchTexts, before, after, m, targetLabel));
        }
        return result;
    }

    /**
     * 翻译一批并保证返回条数与输入严格一致。
     * 模型返回条数不符时（常见于弱模型合并/漏译相邻短句），不做整批反馈重试（实测重试常更差），
     * 而是二分小批重译——批量越小模型越不易出错；批次不大于 SPLIT_MIN 仍失败则逐条兜底。
     * 典型路径：25 条失败 → 12+13 两个小批成功，共 3 次调用（原方案需 1+1+25 次）。
     */
    private List<String> translateBatch(List<String> texts, List<String> before, List<String> after,
                                        ModelEntity m, String targetLabel) {
        List<String> translated;
        try {
            translated = callTranslate(texts, before, after, m, targetLabel);
        } catch (BizException be) {
            // 整批调用失败（超时/返回格式异常等）：按条数不符同样走二分/逐条降级
            log.warn("批量翻译调用失败（{} 条）: {}", texts.size(), be.getMessage());
            translated = List.of();
        }
        if (translated.size() == texts.size()) {
            return translated;
        }
        if (texts.size() <= SPLIT_MIN) {
            log.warn("批量翻译条数不符（{} != {}），降级为逐条翻译", translated.size(), texts.size());
            return translateOneByOne(texts, m, targetLabel);
        }
        int mid = texts.size() / 2;
        log.warn("批量翻译条数不符（{} != {}），二分重译 {}/{} 条",
                translated.size(), texts.size(), mid, texts.size() - mid);
        // 子批内部条目彼此相邻即互为上下文，不再附带跨批上下文
        List<String> out = new ArrayList<>(texts.size());
        out.addAll(translateBatch(texts.subList(0, mid), List.of(), List.of(), m, targetLabel));
        out.addAll(translateBatch(texts.subList(mid, texts.size()), List.of(), List.of(), m, targetLabel));
        return out;
    }

    /** 调用 LLM 翻译一批字幕。 */
    private List<String> callTranslate(List<String> texts, List<String> before, List<String> after,
                                       ModelEntity m, String targetLabel) {
        // 系统提示词来自租户级字幕翻译模板（支持 {{目标语言}} 占位符），模板不存在时自动播种默认值
        String system = promptTemplateService.renderSubtitle(
                promptTemplateService.getSubtitleTemplate(TenantContext.require().tenantId()), targetLabel);

        StringBuilder user = new StringBuilder();
        user.append("请将下面的字幕翻译成").append(targetLabel).append("。\n\n");
        if (!before.isEmpty()) {
            user.append("[上下文·前]\n");
            for (int i = 0; i < before.size(); i++) {
                user.append("- ").append(before.get(i)).append("\n");
            }
            user.append("\n");
        }
        user.append("[待翻译·请逐条对应输出]（以下共 ").append(texts.size()).append(" 条）\n");
        for (int i = 0; i < texts.size(); i++) {
            user.append(i + 1).append(". ").append(texts.get(i)).append("\n");
        }
        if (!after.isEmpty()) {
            user.append("\n[上下文·后]\n");
            for (int i = 0; i < after.size(); i++) {
                user.append("- ").append(after.get(i)).append("\n");
            }
        }
        user.append("\n输出格式：只允许输出一个 JSON 字符串数组，不要 markdown 代码围栏、序号或任何解释文字。")
                .append("数组必须恰好包含 ").append(texts.size())
                .append(" 个字符串元素，与上面 ").append(texts.size())
                .append(" 条输入顺序一一对应；每条字幕即使本身含多句话，也只能产出一个元素，禁止合并或拆分。")
                .append("元素只能是字符串；字符串内双引号用 \\\" 转义、换行用 \\n 表示，空条目输出 \"\"。")
                .append("示例（输入 2 条）：[")
                .append("\"译文1\",\"译文2\"]");

        try {
            var msg = llmClient.chatOnce(m.getBaseUrl(), m.getApiKey(), m.getModel(),
                    List.of(new LlmClient.LlmMessage("system", system),
                            new LlmClient.LlmMessage("user", user.toString())),
                    null,
                    new LlmClient.ChatParams(java.math.BigDecimal.valueOf(0.3),
                            java.math.BigDecimal.valueOf(0.95), 4096),
                    120);
            String content = msg.path("content").asText("");
            return parseJsonArray(content, texts);
        } catch (BizException be) {
            throw be;
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM, "翻译调用失败: " + ex.getMessage());
        }
    }

    /** 逐条翻译兜底：每条独立调用，失败保留原文，保证输出条数与输入严格一致。 */
    private List<String> translateOneByOne(List<String> texts, ModelEntity m, String targetLabel) {
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            try {
                out.add(callTranslate(List.of(t), List.of(), List.of(), m, targetLabel).get(0));
            } catch (Exception ex) {
                log.warn("单条翻译失败，保留原文: {}", ex.getMessage());
                out.add(t);
            }
        }
        return out;
    }

    /** 从模型输出中提取 JSON 字符串数组；单条场景严格校验并容错"原文+译文"双元素，批量场景条数交由上层校验以支持纠错重试。 */
    private List<String> parseJsonArray(String content, List<String> texts) {
        int expected = texts.size();
        if (content == null || content.isBlank()) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM, "模型返回空内容");
        }
        String s = content.trim();
        // 去除 markdown 代码围栏
        if (s.startsWith("```")) {
            int fence = s.indexOf("\n");
            if (fence > 0) {
                s = s.substring(fence + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        // 截取首个 [ 到末个 ]
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM,
                    "模型返回非 JSON 数组格式: " + truncate(s));
        }
        String json = s.substring(start, end + 1);
        try {
            List<String> arr = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            if (expected == 1) {
                // 单条场景严格校验：容错 [原文, 译文] 双元素（取与原文不同的那条），其余条数异常抛出由逐条兜底保留原文
                if (arr.size() == 1) {
                    return arr;
                }
                if (arr.size() == 2) {
                    String src = texts.get(0);
                    return List.of(arr.get(0).trim().equals(src.trim()) ? arr.get(1) : arr.get(0));
                }
                throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM,
                        "翻译条数不匹配（" + arr.size() + " != 1）");
            }
            // 批量场景不在此校验条数：原样返回，由上层二分小批重译/逐条兜底保证条数对齐
            return arr;
        } catch (IOException e) {
            throw new BizException(com.rag.api.common.ErrorCode.UPSTREAM,
                    "解析翻译结果 JSON 失败: " + e.getMessage());
        }
    }

    private String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    // ---------------- 删除 ----------------

    /**
     * 删除字幕记录：级联逻辑删除字幕条、物理清理"原始上传归档"（MinIO 对象 + 文件库条目）；
     * 字幕条备份（subtitle_cue_backup）刻意保留——文件库中翻译保存的归档在主记录删除后仍可据此编辑；
     * 翻译/编辑后保存到文件库的归档（SUBTITLE_SAVE）同样保留，可在文件库中单独管理。
     */
    @Transactional
    public void delete(long id) {
        userManageService.requireMenu("subtitle");
        SubtitleEntity e = requireOwned(id);
        cueMapper.logicDeleteBySubtitleId(e.getId());
        fileLibraryService.removeSubtitleUpload(e.getId());
        subtitleMapper.logicDeleteById(e.getId());
        log.info("字幕记录已删除 subtitle={}", e.getId());
    }

    // ---------------- 编辑 ----------------

    @Transactional
    public Dtos.SubtitleView update(long id, List<Dtos.SubtitleCue> cues) {
        userManageService.requireMenu("subtitle");
        SubtitleEntity e = requireOwned(id);
        List<SubtitleCueEntity> rows = requireCues(e);
        if (cues == null || cues.size() != rows.size()) {
            throw BizException.badRequest("字幕条数与原记录不一致（"
                    + (cues == null ? 0 : cues.size()) + " != " + rows.size() + "）");
        }
        for (int i = 0; i < rows.size(); i++) {
            SubtitleCueEntity row = rows.get(i);
            String text = cues.get(i).text() == null ? "" : cues.get(i).text();
            // 译文允许人工修改：空白按未翻译（null）处理；时间轴强制取已存记录，前端传入的时间轴被忽略
            String translated = cues.get(i).translated();
            translated = translated == null || translated.isBlank() ? null : translated;
            boolean contentChanged = !text.equals(row.getContent());
            boolean translatedChanged = !Objects.equals(translated, row.getTranslatedText());
            if (contentChanged || translatedChanged) {
                // updateById 默认跳过 null 字段，清空译文需显式 set
                if (contentChanged) {
                    row.setContent(text);
                }
                if (translatedChanged) {
                    row.setTranslatedText(translated);
                }
                cueMapper.updateContentAndTranslatedById(row.getId(), row.getContent(), row.getTranslatedText());
                cueBackupMapper.updateContentAndTranslatedBySubtitleIdAndSeq(
                        e.getId(), row.getSeq(), row.getContent(), row.getTranslatedText());
            }
        }
        // 同步归档到文件库（每次保存新增一条，文件名带目标语言；归档失败不阻断保存主流程）
        try {
            fileLibraryService.saveFromSubtitle(e.getTenantId(), e.getUserId(), e.getId(),
                    srtName(e.getOriginalName(), e.getTargetLang()), buildSrt(rows));
        } catch (Exception ex) {
            log.warn("文件库归档失败（不影响字幕保存）: {}", ex.getMessage());
        }
        return toView(e, rows);
    }

    // ---------------- 下载 ----------------

    public byte[] download(long id) {
        SubtitleEntity e = requireOwned(id);
        return buildSrt(requireCues(e)).getBytes(StandardCharsets.UTF_8);
    }

    /** 详情行 → SRT 文本（优先译文，未翻译条目保留原文）。 */
    private String buildSrt(List<SubtitleCueEntity> rows) {
        List<SubtitleCodec.Cue> cues = rows.stream()
                .map(r -> new SubtitleCodec.Cue(r.getStartTime(), r.getEndTime(),
                        r.getTranslatedText() == null || r.getTranslatedText().isBlank()
                                ? r.getContent() : r.getTranslatedText()))
                .toList();
        return SubtitleCodec.toSrt(cues);
    }

    /**
     * 原文件名 + 目标语言 → SRT 文件名（如 01_繁體中文.srt）；未翻译过保持原名。
     * 语言名来自维护列表（用户可自由输入），进入文件名前清理 Windows/MinIO 非法字符。
     */
    public String srtName(String originalName, String targetLang) {
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String suffix = targetLang == null || targetLang.isBlank() ? ""
                : "_" + targetLang.replaceAll("[\\\\/:*?\"<>|]", "-");
        return base + suffix + ".srt";
    }

    // ---------------- 私有 ----------------

    private SubtitleEntity requireOwned(long id) {
        SubtitleEntity e = subtitleMapper.selectById(id);
        TenantContext.Session s = TenantContext.require();
        if (e == null || !s.tenantId().equals(e.getTenantId())
                || e.getUserId() == null || s.userId() != e.getUserId()) {
            throw BizException.notFound("字幕不存在");
        }
        return e;
    }

    private Dtos.SubtitleView toView(SubtitleEntity e, List<SubtitleCueEntity> rows) {
        List<Dtos.SubtitleCue> viewCues = new ArrayList<>(rows.size());
        for (SubtitleCueEntity r : rows) {
            viewCues.add(new Dtos.SubtitleCue(r.getSeq(), r.getStartTime(), r.getEndTime(),
                    r.getContent(), r.getTranslatedText()));
        }
        return new Dtos.SubtitleView(e.getId(), e.getOriginalName(), e.getSourceLang(), e.getTargetLang(),
                viewCues, e.getCreatedAt(), e.getUpdatedAt());
    }

    private Dtos.SubtitleListItem toListItem(SubtitleEntity e) {
        Long cnt = cueMapper.countBySubtitleId(e.getId());
        int count = cnt == null ? 0 : cnt.intValue();
        if (count == 0) {
            // 旧记录未迁移：按原始 SRT 估算条数
            count = SubtitleCodec.parseSrt(e.getSrtContent()).size();
        }
        return new Dtos.SubtitleListItem(e.getId(), e.getOriginalName(), e.getSourceLang(),
                e.getTargetLang(), count, e.getUpdatedAt());
    }
}

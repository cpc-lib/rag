package com.rag.worker.pipeline.chunk;

import com.rag.worker.pipeline.ParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF_LAYOUT（指南 §23/24）：解析阶段已按版面坐标产出有序页文本
 * （含 bbox 表格 Markdown 与内嵌图片识别结果，见 PdfLayoutExtractor），
 * 本策略在此基础上统计并剔除跨页重复的首行/末行（页眉/页脚），
 * 清理后的页交给父子切片策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfLayoutStrategy implements ChunkStrategy {

    private static final double REPEAT_RATIO = 0.6;

    private final ParentChildStrategy parentChildStrategy;

    @Override
    public ChunkMode mode() {
        return ChunkMode.PDF_LAYOUT;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        List<ParseService.RawPage> pages = new ArrayList<>(ctx.pages());
        pages.sort(Comparator.comparingInt(ParseService.RawPage::page));
        return buildPlans(ctx, pages);
    }

    private List<ChunkPlan> buildPlans(ChunkContext ctx, List<ParseService.RawPage> pages) {
        // 页眉/页脚：各页首行/末行在超过 60% 页面重复 → 视为重复
        Map<String, Integer> firstFreq = new HashMap<>();
        Map<String, Integer> lastFreq = new HashMap<>();
        for (ParseService.RawPage p : pages) {
            String[] lines = p.text() == null ? new String[0] : p.text().split("\n");
            bump(firstFreq, lines.length == 0 ? null : lines[0].strip());
            bump(lastFreq, lines.length == 0 ? null : lines[lines.length - 1].strip());
        }
        double total = Math.max(1, pages.size());

        List<ParseService.RawPage> cleanedPages = new ArrayList<>();
        for (ParseService.RawPage page : pages) {
            String original = page.text() == null ? "" : page.text();
            String text = stripRepeating(original, firstFreq, lastFreq, total);
            if (text.isBlank()) {
                continue;
            }
            // 未删除任何行时文本与偏移保持一致，保留块区间用于 bbox 元数据；删行后偏移失效则只保留页码
            List<ParseService.BlockSpan> spans = text.equals(original)
                    ? page.spans() : List.of();
            cleanedPages.add(new ParseService.RawPage(page.page(), text, spans));
        }
        return parentChildStrategy.plan(new ChunkContext(ctx.tenantId(), ctx.documentId(),
                ctx.fileName(), ctx.objectKey(), cleanedPages, ctx.params()));
    }

    private String stripRepeating(String text, Map<String, Integer> first,
                                  Map<String, Integer> last, double total) {
        if (text == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        String[] lines = text.split("\n");
        boolean removed = false;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            boolean isRepeating = total > 1 && ((i == 0 && first.getOrDefault(s, 0) / total > REPEAT_RATIO)
                    || (i == lines.length - 1
                            && last.getOrDefault(s, 0) / total > REPEAT_RATIO));
            if (isRepeating) {
                removed = true;
            } else {
                kept.add(lines[i]);
            }
        }
        return removed ? String.join("\n", kept).strip() : text;
    }

    private void bump(Map<String, Integer> freq, String key) {
        if (key != null && !key.isBlank()) {
            freq.merge(key, 1, Integer::sum);
        }
    }
}

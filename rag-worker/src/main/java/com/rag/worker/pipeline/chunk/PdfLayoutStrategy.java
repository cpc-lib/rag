package com.rag.worker.pipeline.chunk;

import com.rag.worker.infrastructure.storage.MinioStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PDF_LAYOUT（指南 §23/24）：重读原始 PDF，按坐标排序提取阅读顺序
 * （sortByPosition 改善多栏），统计并剔除重复页眉/页脚；文本层为空时
 * 回退解析阶段产出的 OCR 文本；最后按段落+递归打包。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfLayoutStrategy implements ChunkStrategy {

    private static final double REPEAT_RATIO = 0.6;

    private final ChunkSupport support;
    private final MinioStorage minioStorage;

    @Override
    public ChunkMode mode() {
        return ChunkMode.PDF_LAYOUT;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        if (!"pdf".equals(ext(ctx.fileName())) || ctx.objectKey() == null) {
            return fallback(ctx);
        }
        try {
            byte[] bytes;
            try (var in = minioStorage.download(ctx.objectKey())) {
                bytes = in.readAllBytes();
            }
            List<String> pageTexts = new ArrayList<>();
            try (PDDocument pdf = PDDocument.load(bytes)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    pageTexts.add(stripper.getText(pdf));
                }
            }
            return buildPlans(ctx, pageTexts);
        } catch (Exception e) {
            log.warn("PDF 版式切片失败，回退解析文本 doc={}: {}", ctx.documentId(), e.getMessage());
            return fallback(ctx);
        }
    }

    private List<ChunkPlan> buildPlans(ChunkContext ctx, List<String> pageTexts) {
        // 页眉/页脚：各页首行/末行在超过 60% 页面重复 → 视为重复
        Map<String, Integer> firstFreq = new HashMap<>();
        Map<String, Integer> lastFreq = new HashMap<>();
        for (String t : pageTexts) {
            String[] lines = t == null ? new String[0] : t.split("\n");
            bump(firstFreq, lines.length == 0 ? null : lines[0].strip());
            bump(lastFreq, lines.length == 0 ? null : lines[lines.length - 1].strip());
        }
        double total = Math.max(1, pageTexts.size());

        int size = ctx.params().childSize();
        int overlap = ctx.params().overlap();
        List<ChunkPlan> plans = new ArrayList<>();
        for (int i = 0; i < pageTexts.size(); i++) {
            String text = stripRepeating(pageTexts.get(i), firstFreq, lastFreq, total);
            if (text.isBlank() && i < ctx.pages().size()) {
                text = ctx.pages().get(i).text(); // OCR 回退
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            List<ChunkSupport.PackPiece> pieces = new ArrayList<>();
            for (String para : support.paragraphs(text)) {
                for (String part : support.recursive(para, ctx.params().separators())) {
                    for (String bounded : support.hardSplit(part, size)) {
                        pieces.add(ChunkSupport.PackPiece.text(i, bounded));
                    }
                }
            }
            List<PlannedChild> children = support.pack(pieces, size, overlap);
            if (!children.isEmpty()) {
                plans.add(ChunkPlan.independent(null, null, children));
            }
        }
        return plans;
    }

    private String stripRepeating(String text, Map<String, Integer> first,
                                  Map<String, Integer> last, double total) {
        if (text == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            boolean isRepeating = (i == 0 && first.getOrDefault(s, 0) / total > REPEAT_RATIO)
                    || (i == lines.length - 1
                            && last.getOrDefault(s, 0) / total > REPEAT_RATIO);
            if (!isRepeating) {
                kept.add(lines[i]);
            }
        }
        return String.join("\n", kept).strip();
    }

    /** 无法按版式处理时：用解析文本走递归切片。 */
    private List<ChunkPlan> fallback(ChunkContext ctx) {
        int size = ctx.params().childSize();
        int overlap = ctx.params().overlap();
        List<ChunkPlan> plans = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<ChunkSupport.PackPiece> pieces = new ArrayList<>();
            for (String part : support.recursive(text, ctx.params().separators())) {
                for (String bounded : support.hardSplit(part, size)) {
                    pieces.add(ChunkSupport.PackPiece.text(page.page(), bounded));
                }
            }
            List<PlannedChild> children = support.pack(pieces, size, overlap);
            if (!children.isEmpty()) {
                plans.add(ChunkPlan.independent(null, null, children));
            }
        }
        return plans;
    }

    private void bump(Map<String, Integer> freq, String key) {
        if (key != null && !key.isBlank()) {
            freq.merge(key, 1, Integer::sum);
        }
    }

    private String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

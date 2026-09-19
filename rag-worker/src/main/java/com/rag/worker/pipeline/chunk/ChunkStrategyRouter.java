package com.rag.worker.pipeline.chunk;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 策略路由（指南 §35/42）：显式策略直接取对应 bean；AUTO 按文件类型解析：
 * txt→RECURSIVE，md→MARKDOWN，html→HTML，docx→STRUCTURE，xlsx/csv→TABLE，
 * pdf→PDF_LAYOUT，代码文件→CODE，图片(OCR)→RECURSIVE。
 */
@Component
@RequiredArgsConstructor
public class ChunkStrategyRouter {

    private final List<ChunkStrategy> strategyBeans;
    private final Map<ChunkMode, ChunkStrategy> strategies = new EnumMap<>(ChunkMode.class);

    @PostConstruct
    void init() {
        for (ChunkStrategy s : strategyBeans) {
            strategies.put(s.mode(), s);
        }
    }

    public ChunkStrategy route(String configured, String fileName) {
        ChunkMode mode;
        try {
            mode = ChunkMode.valueOf(
                    (configured == null || configured.isBlank() ? "AUTO" : configured).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("未知切片策略: " + configured);
        }
        if (mode != ChunkMode.AUTO) {
            ChunkStrategy s = strategies.get(mode);
            if (s == null) {
                throw new IllegalStateException("切片策略未实现: " + mode);
            }
            return s;
        }
        return strategies.get(autoMode(fileName));
    }

    private ChunkMode autoMode(String fileName) {
        String ext;
        int dot = fileName.lastIndexOf('.');
        ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "md", "markdown" -> ChunkMode.MARKDOWN;
            case "html", "htm" -> ChunkMode.HTML;
            case "doc", "docx" -> ChunkMode.STRUCTURE;
            case "xls", "xlsx", "csv" -> ChunkMode.TABLE;
            case "pdf" -> ChunkMode.PDF_LAYOUT;
            case "java", "py", "go", "js", "jsx", "ts", "tsx" -> ChunkMode.CODE;
            // txt/log/图片 OCR 文本及其余类型：递归切片
            default -> ChunkMode.RECURSIVE;
        };
    }
}

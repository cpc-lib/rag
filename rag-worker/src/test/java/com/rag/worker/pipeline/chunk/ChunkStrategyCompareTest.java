package com.rag.worker.pipeline.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.worker.pipeline.ParseService;
import com.rag.worker.pipeline.TokenCounter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG-009 切片策略前后对比：同一固定样本（标题/段落/表格/代码）走各切片策略，
 * 输出 chunk 数量、长度分布与 token 估算到 {@code target/eval/chunk-strategy-compare.json}，
 * 用于评估策略变更对切片粒度与检索单元数量的影响。全程离线、可复现。
 */
class ChunkStrategyCompareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void compareStrategiesOnFixedSample() throws Exception {
        String sample = sample();
        ChunkSupport support = new ChunkSupport(new TokenCounter());
        ChunkParams params = new ChunkParams(400, 120, 20, List.of("\n\n", "\n", "。", "！", "？", " "));
        ChunkContext context = new ChunkContext("t-compare", 1L, "sample.md", "t/sample.md",
                List.of(new ParseService.RawPage(0, sample)), params);

        Map<String, ChunkStrategy> strategies = new LinkedHashMap<>();
        strategies.put("FIXED_SIZE", new FixedSizeStrategy(support));
        strategies.put("RECURSIVE", new RecursiveStrategy(support));
        strategies.put("PARAGRAPH", new ParagraphStrategy(support));
        strategies.put("SENTENCE", new SentenceStrategy(support));
        strategies.put("SLIDING_WINDOW", new SlidingWindowStrategy(support));
        strategies.put("PARENT_CHILD", new ParentChildStrategy(support));
        strategies.put("MARKDOWN", new MarkdownStrategy(support));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, ChunkStrategy> entry : strategies.entrySet()) {
            List<ChunkPlan> plans = entry.getValue().plan(context);
            List<String> children = new ArrayList<>();
            int parents = 0;
            for (ChunkPlan plan : plans) {
                if (plan.parentChild()) {
                    parents++;
                }
                for (PlannedChild child : plan.children()) {
                    if (child.content() != null && !child.content().isBlank()) {
                        children.add(child.content());
                    }
                }
            }
            assertFalse(children.isEmpty(), entry.getKey() + " 应产出非空切片");

            int totalChars = children.stream().mapToInt(String::length).sum();
            int totalTokens = children.stream().mapToInt(support::tokens).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", entry.getKey());
            row.put("parentCount", parents);
            row.put("childCount", children.size());
            row.put("avgChars", round(totalChars / (double) children.size()));
            row.put("minChars", children.stream().mapToInt(String::length).min().orElse(0));
            row.put("maxChars", children.stream().mapToInt(String::length).max().orElse(0));
            row.put("totalTokens", totalTokens);
            row.put("sampleHead", children.get(0).length() > 40
                    ? children.get(0).substring(0, 40) + "..." : children.get(0));
            rows.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", "内置固定样本（标题/段落/表格/代码）");
        report.put("sampleChars", sample.length());
        report.put("params", Map.of("parentSize", params.parentSize(),
                "childSize", params.childSize(), "overlap", params.overlap()));
        report.put("strategies", rows);
        Path out = Path.of("target", "eval", "chunk-strategy-compare.json");
        Files.createDirectories(out.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);

        // 结构差异断言：Parent/Child 产生 parent 行，纯段落/句子策略不产生
        Map<String, Object> parentChild = rows.stream()
                .filter(r -> "PARENT_CHILD".equals(r.get("strategy"))).findFirst().orElseThrow();
        assertTrue((int) parentChild.get("parentCount") > 0, "PARENT_CHILD 应产生 parent 行");
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String sample() {
        return """
                # 产品概述

                本系统面向企业知识库场景，提供文档解析、切片、向量化与混合检索能力。
                用户可以在控制台上传文档，系统自动完成解析与索引构建。

                ## 检索流程

                检索阶段同时执行向量召回与关键词召回，再通过 RRF 融合得到候选集合。
                启用精排后，对候选执行二阶段相关性排序，提升最终上下文质量。

                ### 表格示例

                | 指标 | 基线 | 优化后 |
                | --- | --- | --- |
                | Recall@5 | 0.72 | 0.81 |
                | MRR@5 | 0.65 | 0.74 |

                ### 代码示例

                ```java
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                ```

                最后一段用于验证切片边界的处理，包含多个句子。它们应保持语义完整。
                """;
    }
}

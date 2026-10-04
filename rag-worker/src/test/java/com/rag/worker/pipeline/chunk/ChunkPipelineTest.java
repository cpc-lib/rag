package com.rag.worker.pipeline.chunk;

import com.rag.worker.pipeline.ParseService;
import com.rag.worker.pipeline.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPipelineTest {

    private final ChunkSupport support = new ChunkSupport(new TokenCounter());

    @Test
    void recursiveSplitKeepsPunctuation() {
        String text = "退款。调用接口，成功！";
        assertEquals(text, String.join("", support.recursive(text, List.of("。", "，", "！"))));
        assertEquals(text, String.join("", support.recursive(text, List.of("", "。", "，", "！"))));
    }

    @Test
    void overlapDoesNotExceedChildBudgetAndTableStaysSeparate() {
        List<PlannedChild> children = support.pack(List.of(
                ChunkSupport.PackPiece.text(0, "甲乙丙丁戊己庚辛"),
                ChunkSupport.PackPiece.text(1, "壬癸子丑寅卯辰巳"),
                new ChunkSupport.PackPiece(1, true, "|列|\n|---|\n|值|")), 11, 4);

        assertEquals(3, children.size());
        assertTrue(children.stream().allMatch(c -> support.tokens(c.content()) <= 11));
        assertEquals(1, children.get(1).page());
        assertEquals("|列|\n|---|\n|值|", children.get(2).content());
    }

    @Test
    void headingHierarchyProducesSearchableParentsWithoutEmptyUnits() {
        ParentChildStrategy strategy = new ParentChildStrategy(support);
        ChunkContext context = new ChunkContext("tenant", 1L, "design.md", null,
                List.of(new ParseService.RawPage(0, "# 系统\n## 退款\n需要审批。"),
                        new ParseService.RawPage(1, "### 渠道\n调用接口。")),
                new ChunkParams(100, 20, 4, List.of("。")));

        List<ChunkPlan> plans = strategy.plan(context);

        assertEquals(2, plans.size());
        assertEquals("系统 > 退款", plans.get(0).sectionPath());
        assertEquals("系统 > 退款 > 渠道", plans.get(1).sectionPath());
        assertTrue(plans.stream().allMatch(p -> !p.children().isEmpty()));
        assertEquals(1, plans.get(1).children().get(0).page());
    }

    @Test
    void largeTableRepeatsHeaderEveryFiftyRows() {
        String table = "|商品|数量|\n|---|---|\n" + "|手机|1|\n".repeat(51);
        List<String> groups = support.splitTable(table.stripTrailing(), 1000);

        assertEquals(2, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.startsWith("|商品|数量|\n|---|---|\n")));
        assertEquals(52, groups.get(0).lines().count());
        assertEquals(3, groups.get(1).lines().count());
    }
}

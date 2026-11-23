package com.rag.api.eval;

import com.rag.api.application.RetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RAG-009：指标口径与 RRF 排序的可复现性。 */
class RetrievalMetricsTest {

    @Test
    void recallPrecisionAndMrrMatchHandCalculation() {
        List<Long> ranked = List.of(1L, 2L, 3L, 4L);
        Set<Long> relevant = Set.of(2L, 4L);

        assertEquals(0.5, RetrievalMetrics.recallAtK(ranked, relevant, 2), 1e-9);
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranked, relevant, 2), 1e-9);
        assertEquals(0.5, RetrievalMetrics.mrrAtK(ranked, relevant, 4), 1e-9);
        assertEquals(1.0, RetrievalMetrics.recallAtK(ranked, relevant, 10), 1e-9);
        assertEquals(0.2, RetrievalMetrics.precisionAtK(ranked, relevant, 10), 1e-9);
    }

    @Test
    void ndcgUsesGradedGain() {
        List<Long> ranked = List.of(2L, 3L, 1L);
        Map<Long, Double> gains = Map.of(1L, 3.0, 2L, 0.0, 3L, 1.0);

        // DCG = 0 + 1/log2(3) + 7/log2(4) = 4.13093；IDCG = 7 + 1/log2(3) = 7.63093
        assertEquals(0.54134, RetrievalMetrics.ndcgAtK(ranked, gains, 3), 1e-4);
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(ranked, Map.of(), 3), 1e-9);
    }

    @Test
    void emptyRelevantSetYieldsZero() {
        List<Long> ranked = List.of(1L, 2L);
        assertEquals(0.0, RetrievalMetrics.recallAtK(ranked, Set.of(), 5), 1e-9);
        assertEquals(0.0, RetrievalMetrics.mrrAtK(ranked, Set.of(), 5), 1e-9);
    }

    @Test
    void rrfFusionIsDeterministicAndTieBreaksByFirstAppearance() {
        List<Long> first = RetrievalService.rrfFuse(List.of(1L, 2L), List.of(2L, 1L), 60, 2);
        List<Long> second = RetrievalService.rrfFuse(List.of(1L, 2L), List.of(2L, 1L), 60, 2);

        assertEquals(List.of(1L, 2L), first);
        assertEquals(first, second);
    }
}

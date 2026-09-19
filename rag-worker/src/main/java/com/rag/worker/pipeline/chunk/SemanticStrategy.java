package com.rag.worker.pipeline.chunk;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rag.worker.infrastructure.llm.EmbeddingClient;
import com.rag.worker.infrastructure.persistence.entity.ModelEntity;
import com.rag.worker.infrastructure.persistence.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SEMANTIC（指南 §8）：句向量相邻余弦相似度低于阈值处断片（主题切换点），
 * 主题内再聚合到 childSize。该模式会额外产生句子级 embedding 调用。
 */
@Component
@RequiredArgsConstructor
public class SemanticStrategy implements ChunkStrategy {

    /** 相邻句相似度低于该值判定为主题边界（指南 §43 自定义参数，默认 0.75）。 */
    private static final double SIMILARITY_THRESHOLD = 0.75;

    private final ChunkSupport support;
    private final EmbeddingClient embeddingClient;
    private final ModelMapper modelMapper;

    @Override
    public ChunkMode mode() {
        return ChunkMode.SEMANTIC;
    }

    @Override
    public List<ChunkPlan> plan(ChunkContext ctx) {
        ModelEntity cfg = modelMapper.selectOne(
                new QueryWrapper<ModelEntity>()
                        .eq("tenant_id", ctx.tenantId())
                        .eq("type", "EMBEDDING").eq("enabled", 1));
        if (cfg == null || cfg.getModel() == null || cfg.getApiKey() == null) {
            throw new IllegalStateException("租户未启用向量(EMBEDDING)模型，无法执行语义切片");
        }

        record Sent(int page, String text) {
        }
        List<Sent> all = new ArrayList<>();
        for (var page : ctx.pages()) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String s : support.sentences(text)) {
                all.add(new Sent(page.page(), s));
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = embeddingClient.embed(
                cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(),
                all.stream().map(Sent::text).toList());

        // 按主题分组：相邻相似度 < 阈值处断开
        List<List<Sent>> groups = new ArrayList<>();
        List<Sent> group = new ArrayList<>();
        group.add(all.get(0));
        for (int i = 1; i < all.size(); i++) {
            if (cosine(vectors.get(i - 1), vectors.get(i)) < SIMILARITY_THRESHOLD) {
                groups.add(group);
                group = new ArrayList<>();
            }
            group.add(all.get(i));
        }
        groups.add(group);

        int size = ctx.params().childSize();
        List<PlannedChild> children = new ArrayList<>();
        for (List<Sent> g : groups) {
            List<ChunkSupport.PackPiece> pieces = g.stream()
                    .map(s -> ChunkSupport.PackPiece.text(s.page(), s.text())).toList();
            // 主题内聚合；不同主题间不做 overlap
            children.addAll(support.pack(pieces, size, 0));
        }
        List<ChunkPlan> plans = new ArrayList<>();
        if (!children.isEmpty()) {
            plans.add(ChunkPlan.independent(null, null, children));
        }
        return plans;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}

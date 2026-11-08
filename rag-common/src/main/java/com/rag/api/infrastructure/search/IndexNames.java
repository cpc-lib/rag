package com.rag.api.infrastructure.search;

/**
 * 知识库索引命名规则（RAG-003 别名级双版本）。
 *
 * <p>逻辑基名固定为 {@code kb.es_index} / {@code kb.milvus_collection}（当前形如 {@code kb_12}）：
 * <ul>
 *   <li>读别名 = {@code {base}_active}，始终指向当前可读版本；</li>
 *   <li>物理名 = {@code version <= 0 ? base : {base}_v{version}}，版本 0 兼容升级前的历史索引。</li>
 * </ul>
 */
public final class IndexNames {

    private static final String ALIAS_SUFFIX = "_active";

    private IndexNames() {
    }

    /** 读别名：检索与对账均走该名称，重建期间无需停读。 */
    public static String alias(String base) {
        return base + ALIAS_SUFFIX;
    }

    /** 版本对应的物理索引/集合名。 */
    public static String physical(String base, Integer version) {
        int v = version == null ? 0 : version;
        return v <= 0 ? base : base + "_v" + v;
    }
}

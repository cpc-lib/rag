package com.rag.worker.reconcile;

/**
 * 巡检外部调用预算：限制单轮 ES/Milvus 调用次数与总耗时，超限后由调用方优雅停止。
 */
public class ReconcileBudget {

    private final int maxCalls;
    private final long deadlineMs;
    private int calls;

    public ReconcileBudget(int maxCalls, long maxDurationMs) {
        this.maxCalls = Math.max(1, maxCalls);
        this.deadlineMs = System.currentTimeMillis() + Math.max(1, maxDurationMs);
    }

    /** 记账并在超限时返回 false。 */
    public boolean spend(int n) {
        calls += Math.max(0, n);
        return !exhausted();
    }

    public boolean exhausted() {
        return calls > maxCalls || System.currentTimeMillis() > deadlineMs;
    }

    public int calls() {
        return calls;
    }
}

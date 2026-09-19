package com.rag.api.common;

public enum ErrorCode {

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或凭证失效"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "资源不存在"),
    QUOTA_EXCEEDED(429, "配额超限"),
    SSE_LIMIT(429, "SSE 并发连接数超限"),
    TOKEN_QUOTA(429, "Token 月度配额超限"),
    INTERNAL(500, "系统内部错误"),
    UPSTREAM(502, "上游模型/外部服务调用失败");

    public final int httpStatus;
    public final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}

package com.aiconnecting.common;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.UUID;

/**
 * SSE (Server-Sent Events) 相关工具方法
 */
public final class SseUtils {

    private SseUtils() {}

    /**
     * 面向终端用户展示的通用错误信息：不包含任何上游细节（模型名、配额、机构 id 等），
     * 详细信息只落地到服务端日志，通过 traceId 关联排查
     */
    public static final String GENERIC_UPSTREAM_ERROR_MESSAGE = "上游服务暂时不可用，请稍后重试";

    /**
     * 设置 SSE 标准响应头
     */
    public static void setSseHeaders(HttpServletResponse response) {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
    }

    /**
     * 已经开始向客户端写出数据（响应已提交）后发生上游失败时，按 SSE 协议补写一个 error 事件并结束流，
     * 不能再拼接另一个渠道/成员的输出，也不能静默断开——客户端需要一个明确的终止信号
     */
    public static void writeSseErrorEvent(HttpServletResponse response, String message) throws IOException {
        String clientMessage = sanitizeForClient(message);
        StringBuilder data = new StringBuilder("data: {\"error\":{\"message\":\"")
                .append(escapeJson(clientMessage)).append("\"");
        if (isEndUserRelayPath()) {
            data.append(",\"traceId\":\"").append(currentTraceId()).append("\"");
        }
        data.append("}}\n\n");
        var writer = response.getWriter();
        writer.write("event: error\n");
        writer.write(data.toString());
        writer.flush();
    }

    /**
     * 判断当前请求是否为面向终端用户的中转接口（/v1/**），区别于管理后台/自测接口（/api/**）
     */
    public static boolean isEndUserRelayPath() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return false;
            String uri = attrs.getRequest().getRequestURI();
            return uri != null && uri.startsWith("/v1/");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 面向终端用户的中转接口隐藏上游细节，管理后台/自测接口保留原始详细信息
     */
    public static String sanitizeForClient(String upstreamMessage) {
        return isEndUserRelayPath() ? GENERIC_UPSTREAM_ERROR_MESSAGE : upstreamMessage;
    }

    /**
     * 读取当前请求的链路追踪 id（与日志中 %X{traceId} 一致），缺失时生成一个随机 id 兜底
     */
    public static String currentTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        return traceId;
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    public static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

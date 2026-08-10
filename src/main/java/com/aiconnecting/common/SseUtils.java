package com.aiconnecting.common;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * SSE (Server-Sent Events) 相关工具方法
 */
public final class SseUtils {

    private SseUtils() {}

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
        var writer = response.getWriter();
        writer.write("event: error\n");
        writer.write("data: {\"error\":{\"message\":\"" + escapeJson(message) + "\"}}\n\n");
        writer.flush();
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

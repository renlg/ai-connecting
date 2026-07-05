package com.aiconnecting.common;

import jakarta.servlet.http.HttpServletResponse;

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

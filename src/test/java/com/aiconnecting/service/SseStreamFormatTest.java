package com.aiconnecting.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSE 流式转发格式测试
 * 用本地 HTTP 服务器模拟上游 API 的 SSE 响应，
 * 验证 RelayService 转发给客户端的 SSE 格式是否正确。
 */
class SseStreamFormatTest {

    private HttpServer mockUpstreamServer;
    private int port;

    @BeforeEach
    void startMockServer() throws IOException {
        mockUpstreamServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockUpstreamServer.getAddress().getPort();
        mockUpstreamServer.setExecutor(null);
        mockUpstreamServer.start();
    }

    @AfterEach
    void stopMockServer() {
        if (mockUpstreamServer != null) {
            mockUpstreamServer.stop(0);
        }
    }

    /**
     * 测试1: 验证上游标准 SSE 格式经过 readLine + write 转发后的输出
     * 上游发送:
     *   data: {"choices":[{"delta":{"content":"Hello"}}]}\n
     *   \n
     *   data: {"choices":[{"delta":{"content":" World"}}]}\n
     *   \n
     *   data: [DONE]\n
     *   \n
     */
    @Test
    void testSseFormat_StandardOpenAIStream() throws Exception {
        // 模拟上游返回标准 OpenAI SSE 格式
        String upstreamSse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n" +
                "\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\" World\"}}]}\n" +
                "\n" +
                "data: [DONE]\n" +
                "\n";

        mockUpstreamServer.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(upstreamSse.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            exchange.close();
        });

        // 模拟 RelayService 的流式转发逻辑
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        mockResponse.setContentType("text/event-stream");
        mockResponse.setCharacterEncoding("UTF-8");

        java.net.URL urlObj = new java.net.URL("http://localhost:" + port + "/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        assertEquals(200, conn.getResponseCode());

        // 使用与 RelayService 相同的逻辑读取和写入
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = mockResponse.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    writer.write("\n");
                } else {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.flush();
            }
        }
        conn.disconnect();

        String output = mockResponse.getContentAsString();
        System.out.println("=== OpenAI SSE 输出 (可见转义) ===");
        System.out.println(output.replace("\n", "\\n\n"));
        System.out.println("=== 原始输出 ===");
        System.out.println(output);

        // 验证: 每个 data 事件之间必须有且仅有一个空行(\n\n)分隔
        // 正确的 SSE 格式: data: ...\n\n data: ...\n\n data: [DONE]\n\n
        String[] events = output.split("\n\n");
        // 过滤掉尾部空字符串
        List<String> nonEmptyEvents = new ArrayList<>();
        for (String e : events) {
            if (!e.isEmpty()) {
                nonEmptyEvents.add(e);
            }
        }

        System.out.println("=== 解析出的 SSE 事件数: " + nonEmptyEvents.size() + " ===");
        for (int i = 0; i < nonEmptyEvents.size(); i++) {
            System.out.println("事件 " + i + ": [" + nonEmptyEvents.get(i).replace("\n", "\\n") + "]");
        }

        assertEquals(3, nonEmptyEvents.size(), "应该有3个SSE事件 (2个data + 1个DONE)");
        assertTrue(nonEmptyEvents.get(0).startsWith("data: "), "第1个事件应以 data: 开头");
        assertTrue(nonEmptyEvents.get(0).contains("Hello"), "第1个事件应包含 Hello");
        assertTrue(nonEmptyEvents.get(1).contains("World"), "第2个事件应包含 World");
        assertEquals("data: [DONE]", nonEmptyEvents.get(2), "最后事件应是 data: [DONE]");
    }

    /**
     * 测试2: 验证 Claude 协议 SSE 格式转发
     */
    @Test
    void testSseFormat_ClaudeStream() throws Exception {
        String upstreamSse = "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_123\"}}\n" +
                "\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hi\"}}\n" +
                "\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n" +
                "\n";

        mockUpstreamServer.createContext("/v1/messages", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(upstreamSse.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            exchange.close();
        });

        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        mockResponse.setContentType("text/event-stream");
        mockResponse.setCharacterEncoding("UTF-8");

        java.net.URL urlObj = new java.net.URL("http://localhost:" + port + "/v1/messages");
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        assertEquals(200, conn.getResponseCode());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = mockResponse.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    writer.write("\n");
                } else {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.flush();
            }
        }
        conn.disconnect();

        String output = mockResponse.getContentAsString();
        System.out.println("=== Claude SSE 输出 (可见转义) ===");
        System.out.println(output.replace("\n", "\\n\n"));

        // Claude SSE 有 event: 行和 data: 行，它们之间不应有空行
        // 每个事件块之间用 \n\n 分隔
        // 验证 event: message_start 后面紧跟 data: {...}，然后是 \n\n
        assertTrue(output.contains("event: message_start\n"), "应包含 event: message_start");
        assertTrue(output.contains("data: {\"type\":\"message_start\""), "应包含 message_start data");

        // 验证事件之间的分隔是正确的 \n\n
        // event: message_start\ndata: {...}\n\nevent: content_block_delta\ndata: {...}\n\n...
        String[] blocks = output.split("\n\n");
        List<String> nonEmptyBlocks = new ArrayList<>();
        for (String b : blocks) {
            if (!b.isEmpty()) nonEmptyBlocks.add(b);
        }
        System.out.println("=== Claude 事件块数: " + nonEmptyBlocks.size() + " ===");
        for (int i = 0; i < nonEmptyBlocks.size(); i++) {
            System.out.println("块 " + i + ": [" + nonEmptyBlocks.get(i).replace("\n", "\\n") + "]");
        }

        assertEquals(3, nonEmptyBlocks.size(), "应有3个Claude事件块");
        assertTrue(nonEmptyBlocks.get(0).contains("message_start"), "第1块应是 message_start");
        assertTrue(nonEmptyBlocks.get(1).contains("content_block_delta"), "第2块应是 content_block_delta");
        assertTrue(nonEmptyBlocks.get(2).contains("message_stop"), "第3块应是 message_stop");
    }

    /**
     * 测试3: 对比旧逻辑(每行都加\n\n)的问题
     * 验证旧逻辑会产生多余空行
     */
    @Test
    void testOldLogicProducesExtraBlankLines() throws Exception {
        String upstreamSse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n" +
                "\n" +
                "data: [DONE]\n" +
                "\n";

        mockUpstreamServer.createContext("/old", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(upstreamSse.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            exchange.close();
        });

        // 旧逻辑: 每行都 write(line + "\n\n")
        MockHttpServletResponse oldResponse = new MockHttpServletResponse();
        java.net.URL urlObj = new java.net.URL("http://localhost:" + port + "/old");
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = oldResponse.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                // 旧逻辑
                writer.write(line);
                writer.write("\n\n");
                writer.flush();
            }
        }
        conn.disconnect();

        String oldOutput = oldResponse.getContentAsString();
        System.out.println("=== 旧逻辑输出 (可见转义) ===");
        System.out.println(oldOutput.replace("\n", "\\n\n"));

        // 旧逻辑会产生: data:...\n\n\n\n data:...\n\n\n\n
        // 即每个事件之间有 3 个换行符(2个空行)，而不是正确的 2 个换行符(1个空行)
        // 数一下连续 \n 的出现
        int maxConsecutiveNewlines = 0;
        int currentNewlines = 0;
        for (char c : oldOutput.toCharArray()) {
            if (c == '\n') {
                currentNewlines++;
                maxConsecutiveNewlines = Math.max(maxConsecutiveNewlines, currentNewlines);
            } else {
                currentNewlines = 0;
            }
        }

        System.out.println("旧逻辑最大连续换行数: " + maxConsecutiveNewlines);
        // 旧逻辑会产生 \n\n\n (3个连续换行 = 2个空行)
        assertTrue(maxConsecutiveNewlines >= 3,
                "旧逻辑应产生至少3个连续换行(多余空行), 实际: " + maxConsecutiveNewlines);
    }

    /**
     * 测试4: 验证新逻辑不会产生多余空行
     */
    @Test
    void testNewLogicNoExtraBlankLines() throws Exception {
        String upstreamSse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n" +
                "\n" +
                "data: [DONE]\n" +
                "\n";

        mockUpstreamServer.createContext("/new", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(upstreamSse.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            exchange.close();
        });

        // 新逻辑
        MockHttpServletResponse newResponse = new MockHttpServletResponse();
        java.net.URL urlObj = new java.net.URL("http://localhost:" + port + "/new");
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = newResponse.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                // 新逻辑
                if (line.isEmpty()) {
                    writer.write("\n");
                } else {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.flush();
            }
        }
        conn.disconnect();

        String newOutput = newResponse.getContentAsString();
        System.out.println("=== 新逻辑输出 (可见转义) ===");
        System.out.println(newOutput.replace("\n", "\\n\n"));

        // 新逻辑: data:...\n\n data:...\n\n
        // 最大连续换行应该是 2 (即一个空行分隔)
        int maxConsecutiveNewlines = 0;
        int currentNewlines = 0;
        for (char c : newOutput.toCharArray()) {
            if (c == '\n') {
                currentNewlines++;
                maxConsecutiveNewlines = Math.max(maxConsecutiveNewlines, currentNewlines);
            } else {
                currentNewlines = 0;
            }
        }

        System.out.println("新逻辑最大连续换行数: " + maxConsecutiveNewlines);
        assertEquals(2, maxConsecutiveNewlines,
                "新逻辑最大连续换行应为2(一个空行分隔SSE事件)");
    }

    /**
     * 测试5: 模拟浏览器 EventSource 解析，验证能否正确解析事件
     */
    @Test
    void testEventSourceParsing() throws Exception {
        String upstreamSse = "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n" +
                "\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n" +
                "\n" +
                "data: [DONE]\n" +
                "\n";

        mockUpstreamServer.createContext("/es", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(upstreamSse.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            exchange.close();
        });

        // 用新逻辑转发
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.net.URL urlObj = new java.net.URL("http://localhost:" + port + "/es");
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = response.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    writer.write("\n");
                } else {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.flush();
            }
        }
        conn.disconnect();

        String output = response.getContentAsString();

        // 模拟 EventSource 解析: 按 \n\n 分割事件，每个事件内找 data: 行
        List<String> parsedDataLines = new ArrayList<>();
        String[] events = output.split("\n\n");
        for (String event : events) {
            if (event.trim().isEmpty()) continue;
            for (String eventLine : event.split("\n")) {
                if (eventLine.startsWith("data: ")) {
                    parsedDataLines.add(eventLine.substring(6));
                }
            }
        }

        System.out.println("=== EventSource 解析出的 data 行 ===");
        for (int i = 0; i < parsedDataLines.size(); i++) {
            System.out.println("data[" + i + "]: " + parsedDataLines.get(i));
        }

        assertEquals(3, parsedDataLines.size(), "EventSource 应解析出3个 data 行");
        assertTrue(parsedDataLines.get(0).contains("\"A\""), "第1个data应包含A");
        assertTrue(parsedDataLines.get(1).contains("\"B\""), "第2个data应包含B");
        assertEquals("[DONE]", parsedDataLines.get(2), "最后data应是[DONE]");
    }
}

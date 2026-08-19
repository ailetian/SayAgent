package com.sayagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.mcp.dto.ToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * McpClientManager 单测（M7/T2，§7.10 命名 test方法_场景_预期；不连真网，用 JDK 内置 HttpServer 模拟 MCP 端点）。
 *
 * <p>覆盖：连接+发现工具、执行返回结果、工具不存在抛异常、读超时受控抛异常、地址不可达抛异常、SSE 响应也能解析。
 */
class McpClientManagerTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 模拟服务端响应前的延迟（毫秒），用于构造读超时场景。 */
    private long delayMs = 0;

    @BeforeEach
    void setUp() throws IOException {
        delayMs = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, false));
        server.createContext("/sse", exchange -> handle(exchange, true));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private McpServer httpServer(String type, String addressSuffix) {
        McpServer s = new McpServer();
        s.setId(1L);
        s.setName("mock");
        s.setAddress(baseUrl + (addressSuffix == null ? "" : addressSuffix));
        s.setType(type);
        s.setStatus(1);
        return s;
    }

    private McpClientManager newManager(int connectMs, int readMs) {
        McpConfig cfg = new McpConfig();
        cfg.connectTimeoutMs = connectMs;
        cfg.readTimeoutMs = readMs;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return new McpClientManager(new OkHttpClient(), cfg, executor);
    }

    @Test
    void testListTools_正常连接_发现至少一个工具() {
        McpClientManager mgr = newManager(2000, 5000);
        List<ToolDefinition> tools = mgr.listTools(httpServer("HTTP", ""));
        assertFalse(tools.isEmpty(), "应发现至少一个工具");
        assertEquals("echo", tools.get(0).name());
    }

    @Test
    void testCallTool_正常调用_返回结果文本() {
        McpClientManager mgr = newManager(2000, 5000);
        String result = mgr.callTool(httpServer("HTTP", ""), "echo", "{\"msg\":\"hello\"}");
        assertEquals("echo:hello", result);
    }

    @Test
    void testCallTool_工具不存在_抛McpException() {
        McpClientManager mgr = newManager(2000, 5000);
        BizException ex = assertThrows(BizException.class,
                () -> mgr.callTool(httpServer("HTTP", ""), "notfound", "{}"));
        assertEquals(ErrorCode.MCP_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    void testCallTool_读超时_抛McpException() {
        // 服务端延迟 2s，客户端读超时 500ms → 触发超时（§4.3 超时受控）
        delayMs = 2000;
        McpClientManager mgr = newManager(200, 500);
        BizException ex = assertThrows(BizException.class,
                () -> mgr.callTool(httpServer("HTTP", ""), "echo", "{\"msg\":\"x\"}"));
        assertEquals(ErrorCode.MCP_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    void testConnect_地址不可达_抛McpException() {
        // 端口 1 不可能有服务监听 → 连接被拒 → 统一抛 MCP_CALL_FAILED（§7.3 规则10 不吞异常）
        McpServer bad = new McpServer();
        bad.setId(99L);
        bad.setName("bad");
        bad.setType("HTTP");
        bad.setStatus(1);
        bad.setAddress("http://127.0.0.1:1/mcp");
        McpClientManager mgr = newManager(500, 2000);
        BizException ex = assertThrows(BizException.class, () -> mgr.listTools(bad));
        assertEquals(ErrorCode.MCP_CALL_FAILED, ex.getErrorCode());
    }

    @Test
    void testListTools_SSE响应_也能解析() {
        // SSE 类型走 Streamable HTTP 传输，服务端以 text/event-stream 返回，客户端应能解析
        McpClientManager mgr = newManager(2000, 5000);
        List<ToolDefinition> tools = mgr.listTools(httpServer("SSE", "sse"));
        assertFalse(tools.isEmpty(), "SSE 响应也应能发现工具");
        assertEquals("sseEcho", tools.get(0).name());
    }

    /**
     * 模拟 MCP 服务端：按 JSON-RPC 方法返回对应响应；sse=true 时 tools/list 以 SSE 形式返回。
     */
    private void handle(HttpExchange exchange, boolean sse) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode req = mapper.readTree(body);
        String method = req.path("method").asText();
        long id = req.path("id").isMissingNode() ? -1 : req.path("id").asLong();

        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        String json;
        int status = 200;
        String contentType = "application/json";
        if ("initialize".equals(method)) {
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"mock\",\"version\":\"1.0\"}}}";
        } else if ("tools/list".equals(method)) {
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"tools\":[{\"name\":\"" + (sse ? "sseEcho" : "echo")
                    + "\",\"description\":\"" + (sse ? "sse tool" : "回显工具")
                    + "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}}}}]}}";
            if (sse) {
                contentType = "text/event-stream";
                json = "event: message\ndata: " + json + "\n\n";
            }
        } else if ("tools/call".equals(method)) {
            if ("notfound".equals(req.path("params").path("name").asText())) {
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32602,\"message\":\"tool not found\"}}";
            } else {
                String msg = req.path("params").path("arguments").path("msg").asText();
                json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"echo:" + msg + "\"}],\"isError\":false}}";
            }
        } else if ("notifications/initialized".equals(method)) {
            status = 202;
            json = "";
        } else {
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (status == 202) {
            exchange.sendResponseHeaders(202, 0);
        } else {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}

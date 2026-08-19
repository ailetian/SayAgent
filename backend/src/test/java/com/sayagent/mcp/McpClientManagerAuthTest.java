package com.sayagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.mcp.dto.ToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * McpClientManager 鉴权凭据注入单测（M10/T1，§7.11 敏感不落日志）。
 *
 * <p>大白话：给 McpServer 配了 Bearer/APIKey/自定义头后，真正发 HTTP 请求时必须把凭据塞进
 * {@code Authorization} / 自定义头；免鉴权(NONE)则一个头都不加。本测试用一个会「把收到的 Authorization
 * 头原样回显」的假 MCP 端点来验证——不依赖真实 ERP/飞书，也不打印 token。
 * 命名遵循 {@code test方法_场景_预期}（§7.10 规则34）。
 */
class McpClientManagerAuthTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private McpClientManager newManager() {
        McpConfig cfg = new McpConfig();
        cfg.connectTimeoutMs = 2000;
        cfg.readTimeoutMs = 5000;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return new McpClientManager(new OkHttpClient(), cfg, executor);
    }

    private McpServer serverWith(String authType, String authConfig) {
        McpServer s = new McpServer();
        s.setId(1L);
        s.setName("mock-auth");
        s.setAddress(baseUrl);
        s.setType("HTTP");
        s.setStatus(1);
        s.setAuthType(authType);
        s.setAuthConfig(authConfig);
        return s;
    }

    @Test
    @DisplayName("BEARER：请求带 Authorization: Bearer <token>")
    void testBearer_注入Bearer头() {
        McpClientManager mgr = newManager();
        String result = mgr.callTool(serverWith("BEARER", "{\"token\":\"secret-xyz\"}"), "echo", "{}");
        assertEquals("AUTH:Bearer secret-xyz", result);
    }

    @Test
    @DisplayName("APIKEY：请求带 Authorization: ApiKey <key>")
    void testApiKey_注入ApiKey头() {
        McpClientManager mgr = newManager();
        String result = mgr.callTool(serverWith("APIKEY", "{\"key\":\"ak-123\"}"), "echo", "{}");
        assertEquals("AUTH:ApiKey ak-123", result);
    }

    @Test
    @DisplayName("HEADER：自定义头逐对注入")
    void testHeader_注入自定义头() {
        McpClientManager mgr = newManager();
        // 假端点把 X-Api-Key 头回显，验证自定义头注入链路
        String result = mgr.callTool(serverWith("HEADER", "{\"headers\":{\"X-Api-Key\":\"abc\",\"X-Tenant\":\"t1\"}}"), "echo", "{}");
        assertEquals("XAPIKEY:abc", result);
    }

    @Test
    @DisplayName("NONE：不加任何鉴权头")
    void testNone_不加鉴权头() {
        McpClientManager mgr = newManager();
        String result = mgr.callTool(serverWith("NONE", null), "echo", "{}");
        assertEquals("AUTH:none", result);
    }

    @Test
    @DisplayName("listTools：带鉴权的服务也能发现工具")
    void testListTools_带Bearer_发现工具() {
        McpClientManager mgr = newManager();
        List<ToolDefinition> tools = mgr.listTools(serverWith("BEARER", "{\"token\":\"secret-xyz\"}"));
        assertFalse(tools.isEmpty(), "带鉴权也应能发现工具");
        assertEquals("echo", tools.get(0).name());
    }

    /**
     * 假 MCP 端点：tools/call 把请求里收到的 Authorization / X-Api-Key 头原样回显，
     * 以此证明 McpClientManager 确实把凭据注入了 HTTP 头（且不打印 token）。
     */
    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode req = mapper.readTree(body);
        String method = req.path("method").asText();
        long id = req.path("id").isMissingNode() ? -1 : req.path("id").asLong();

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String xApiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");

        String json;
        int status = 200;
        if ("initialize".equals(method)) {
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"mock\",\"version\":\"1.0\"}}}";
        } else if ("tools/list".equals(method)) {
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"回显\",\"inputSchema\":{\"type\":\"object\"}}]}}";
        } else if ("tools/call".equals(method)) {
            String echoed = (xApiKey != null) ? "XAPIKEY:" + xApiKey : (auth != null ? "AUTH:" + auth : "AUTH:none");
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + echoed + "\"}],\"isError\":false}}";
        } else if ("notifications/initialized".equals(method)) {
            status = 202;
            json = "";
        } else {
            json = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 202) {
            exchange.sendResponseHeaders(202, 0);
        } else {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}

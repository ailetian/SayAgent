package com.hify.hify.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.common.tool.DataSensitivity;
import com.hify.hify.common.tool.RiskLevel;
import com.hify.hify.mcp.dto.ToolDefinition;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * MCP 客户端管理器（M7/T2，§2 模块8 / §3.2 mcp 包自包含 / §4.2 线程 / §4.3 超时 / §4.8 复用 OkHttp 单例）。
 *
 * <p>大白话：这是「翻译+接线员」。拿到 T1 登记的 {@link McpServer} 配置，先拨通对方（连接），
 * 问清楚对方会哪些活儿（发现工具列表），需要时把参数递过去让它干活（执行调用），把结果拿回来。
 *
 * <p>设计要点：
 * <ul>
 *   <li>复用 M3 的 OkHttp 单例（{@code okHttpClient} Bean，§4.8），禁止每次 new；每次调用仅基于它
 *       派生一个带「连接/读超时」的子类（连接池共享，§4.3 两级超时）。</li>
 *   <li>HTTP/SSE 走 {@link HttpTransport}（Streamable HTTP：POST JSON-RPC，响应可以是
 *       application/json 或 text/event-stream）；STDIO 走 {@link StdioTransport}（拉起子进程，用标准输入输出传 JSON-RPC）。</li>
 *   <li>所有失败统一抛 {@link BizException#MCP_CALL_FAILED}，由上层 T3 降级，<b>绝不抛 500 中断对话</b>（§4.5）。</li>
 *   <li>本类方法会阻塞到 MCP 服务端响应（受超时控制）。调用方应在专用线程池（mcpExecutor）里执行，
 *       禁止在数据库事务内调用（§4.2 / §7.5 规则23）；也可用 {@link #callToolAsync} 直接拿 Future。</li>
 * </ul>
 */
@Service
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** MCP 协议版本（initialize 握手用）。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** 本客户端名字（initialize 的 clientInfo）。 */
    private static final String CLIENT_NAME = "sayagent";

    /** 本客户端版本。 */
    private static final String CLIENT_VERSION = "0.0.1";

    /** JSON-RPC 协议标识。 */
    private static final String JSONRPC = "2.0";

    /** 共享 OkHttp 单例（§4.8）。 */
    private final OkHttpClient okHttpClient;

    /** MCP 专用配置（超时等）。 */
    private final McpConfig mcpConfig;

    /** MCP 专用线程池（§4.2）。 */
    private final ExecutorService mcpExecutor;

    /** Jackson 解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已建立的连接，按 server id 缓存（同一条配置只连一次）。 */
    private final Map<Long, McpTransport> sessions = new ConcurrentHashMap<>();

    /**
     * 构造（构造函数注入，§7 风格）。
     *
     * @param okHttpClient 共享 OkHttp 单例（modelprovider 的 okHttpClient Bean）
     * @param mcpConfig    MCP 超时配置
     * @param mcpExecutor  MCP 专用线程池（@Qualifier("mcpExecutor")）
     */
    @Autowired
    public McpClientManager(
            OkHttpClient okHttpClient,
            McpConfig mcpConfig,
            @Qualifier("mcpExecutor") ExecutorService mcpExecutor) {
        this.okHttpClient = okHttpClient;
        this.mcpConfig = mcpConfig;
        this.mcpExecutor = mcpExecutor;
    }

    /**
     * 发现某个 MCP Server 提供的全部工具。
     *
     * @param server 已登记的服务端配置
     * @return 工具定义列表（name/description/inputSchema）
     */
    public List<ToolDefinition> listTools(McpServer server) {
        try {
            return getTransport(server).listTools();
        } catch (BizException e) {
            // 传输层已把网络/超时/解析/协议错误统一转成 BizException(MCP_CALL_FAILED)；
            // 此处仅补记日志并原样上抛，便于上层（T3）按 MCP_CALL_FAILED 降级（§4.5）。
            // 不写 catch(Exception) 一把抓（§7.3 规则11）；其余非预期异常属自身 bug，应向上暴露而非伪装成 MCP 失败。
            log.error("mcp.listTools fail server={}", server.getName(), e);
            throw e;
        }
    }

    /**
     * 调用某个工具，返回结果文本（通常是工具回传的 content[0].text）。
     *
     * @param server       已登记的服务端配置
     * @param toolName     工具名
     * @param argumentsJson 入参 JSON 对象字符串（如 {"msg":"hello"}）
     * @return 工具执行结果文本
     */
    public String callTool(McpServer server, String toolName, String argumentsJson) {
        long start = System.currentTimeMillis();
        try {
            String result = getTransport(server).callTool(toolName, argumentsJson);
            log.info("mcp.call ok server={} tool={} costMs={}", server.getName(), toolName,
                    System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            log.error("mcp.call fail server={} tool={} costMs={}", server.getName(), toolName,
                    System.currentTimeMillis() - start, e);
            throw e;
        }
    }

    /**
     * 异步调用工具（在 mcpExecutor 专用池执行，§4.2）。
     *
     * @param server       已登记的服务端配置
     * @param toolName     工具名
     * @param argumentsJson 入参 JSON 对象字符串
     * @return 结果 Future
     */
    public CompletableFuture<String> callToolAsync(McpServer server, String toolName, String argumentsJson) {
        return CompletableFuture.supplyAsync(() -> callTool(server, toolName, argumentsJson), mcpExecutor);
    }

    /**
     * 关闭某个 Server 的连接并移除缓存。
     *
     * @param serverId 服务端配置 id
     */
    public void close(Long serverId) {
        McpTransport transport = sessions.remove(serverId);
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception e) {
                log.warn("mcp.close ignore serverId={}", serverId, e);
            }
        }
    }

    /** 关闭全部连接（应用关闭时调用）。 */
    public void closeAll() {
        sessions.values().forEach(t -> {
            try {
                t.close();
            } catch (Exception e) {
                log.warn("mcp.closeAll ignore", e);
            }
        });
        sessions.clear();
    }

    /**
     * 取（必要时建）某个 Server 的连接。连接/握手失败统一抛 {@link ErrorCode#MCP_CALL_FAILED}。
     *
     * @param server 服务端配置
     * @return 已初始化的传输层
     */
    private McpTransport getTransport(McpServer server) {
        return sessions.computeIfAbsent(server.getId(), id -> {
            try {
                McpTransport transport = createTransport(server);
                transport.initialize();
                log.info("mcp.connect ok server={} type={}", server.getName(), server.getType());
                return transport;
            } catch (BizException e) {
                // createTransport 不支持类型 / initialize 失败均已转 BizException(MCP_CALL_FAILED)；
                // 不补 catch(Exception)（§7.3 规则11），非预期异常属自身 bug，应交由全局异常处理。
                throw e;
            }
        });
    }

    /**
     * 按配置类型创建对应传输层。HTTP 与 SSE 类型都走 Streamable HTTP 传输（现代 MCP 标准）；
     * 若日后遇到「GET /sse 先握手再 POST」的遗留传输，可在此分支扩展（当前 MVP 不实现，已记录）。
     *
     * @param server 服务端配置
     * @return 传输层
     */
    private McpTransport createTransport(McpServer server) {
        String type = server.getType() == null ? "STDIO" : server.getType().toUpperCase();
        // 凭据头在连接建立时解析一次（覆盖 initialize/tools/list/tools/call 全部请求，§7.3 规则14b）
        Map<String, String> authHeaders = resolveAuthHeaders(server);
        // M10/T4：本 Server 注册时标注的数据敏感度，作为它旗下所有工具的敏感度（MCP 协议无标准敏感度字段，靠管理员标注）
        DataSensitivity ds = toDataSensitivity(server.getDataSensitivity());
        return switch (type) {
            case "STDIO" -> new StdioTransport(server.getAddress(), ds);
            case "HTTP", "SSE" -> new HttpTransport(okHttpClient, server.getAddress(),
                    mcpConfig.getConnectTimeoutMs(), mcpConfig.getReadTimeoutMs(), authHeaders, ds);
            default -> throw new BizException(ErrorCode.MCP_CALL_FAILED, "不支持的 MCP 类型：" + type);
        };
    }

    /**
     * 把 McpServer 存储的 dataSensitivity 字符串安全转成枚举（M10/T4）。
     *
     * <p>防御：null / 空 / 非法值一律兜底为 {@code INTERNAL}（默认分类），绝不抛异常（§7.3 规则10 不空吞但也不暴露）。
     *
     * @param s 存储字符串（如 "FINANCE_HR"）
     * @return 枚举；非法时 INTERNAL
     */
    private static DataSensitivity toDataSensitivity(String s) {
        if (s == null || s.isBlank()) {
            return DataSensitivity.INTERNAL;
        }
        try {
            return DataSensitivity.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return DataSensitivity.INTERNAL;
        }
    }

    /**
     * 构建 initialize 握手的 params（§7.3 规则14b：防御性构造，不依赖外部顺序）。
     *
     * @return params JSON 节点
     */
    private JsonNode buildInitParams() {
        var params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        var clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        return params;
    }

    /**
     * 把 {@link McpServer} 的鉴权配置解析成一组 HTTP 请求头（M10/T1，§7.11 敏感不落日志）。
     *
     * <p>大白话：管理员在 McpServer 上登记的「鉴权类型 + 凭据 JSON」，在这里翻译成具体的 HTTP 头，
     * 由 {@link HttpTransport#rpc} / {@code sendNotification} 在 initialize / tools/list / tools/call 每
     * 一次请求里带上，这样 ERP、飞书这类要登录的内部系统才连得上（免鉴权的 NONE 则不加任何头）。
     *
     * <p>凭据约定（authConfig JSON）：
     * <ul>
     *   <li>{@code BEARER} → {@code {"token":"..."}}  ⇒ {@code Authorization: Bearer <token>}</li>
     *   <li>{@code APIKEY}  → {@code {"key":"..."}}    ⇒ {@code Authorization: ApiKey <key>}</li>
     *   <li>{@code HEADER}  → {@code {"headers":{...}}} ⇒ 逐对注入自定义头</li>
     * </ul>
     *
     * <p>健壮性：authType 为 null/NONE、或 authConfig 为空 / 解析失败时，<b>返回空集合</b>（不抛异常），
     * 留待真实连接时由服务端 401 再统一转 {@code MCP_CALL_FAILED} 降级（§4.5）。整段逻辑绝不打印 token。
     *
     * @param server 已登记的服务端配置
     * @return 需注入的请求头（有序 Map，可能为空）
     */
    private Map<String, String> resolveAuthHeaders(McpServer server) {
        Map<String, String> headers = new LinkedHashMap<>();
        String authType = server.getAuthType();
        if (authType == null || "NONE".equalsIgnoreCase(authType)) {
            return headers;
        }
        String cfg = server.getAuthConfig();
        if (cfg == null || cfg.isBlank()) {
            log.debug("mcp auth_type={} 但 auth_config 为空，按无凭据连接 server={}", authType, server.getName());
            return headers;
        }
        try {
            JsonNode node = objectMapper.readTree(cfg);
            switch (authType.toUpperCase()) {
                case "BEARER" -> {
                    String token = node.path("token").asText(null);
                    if (token != null && !token.isBlank()) {
                        headers.put("Authorization", "Bearer " + token);
                    }
                }
                case "APIKEY" -> {
                    String key = node.path("key").asText(null);
                    if (key != null && !key.isBlank()) {
                        headers.put("Authorization", "ApiKey " + key);
                    }
                }
                case "HEADER" -> {
                    JsonNode hs = node.get("headers");
                    if (hs != null && hs.isObject()) {
                        hs.fields().forEachRemaining(e -> {
                            String v = e.getValue().asText();
                            if (!v.isBlank()) {
                                headers.put(e.getKey(), v);
                            }
                        });
                    }
                }
                default -> log.debug("mcp 未知 auth_type={} server={}", authType, server.getName());
            }
        } catch (IOException e) {
            // 凭据 JSON 损坏：不抛（连接时多半 401），降级为无凭据，仅记 DEBUG（§7.3 规则10 不空吞但也不暴露 token）
            log.debug("mcp auth_config 解析失败 server={}", server.getName(), e);
        }
        return headers;
    }

    /**
     * 安全解析 JSON（捕获 JsonProcessingException 转业务异常）。
     *
     * @param text JSON 文本
     * @return 解析结果
     */
    private JsonNode readTree(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (IOException e) {
            throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP 响应不是合法 JSON：" + e.getMessage());
        }
    }

    /**
     * 从 JSON-RPC 响应节点里取出 result；若含 error 或 isError 则抛业务异常（§7.3 规则10 不吞异常）。
     *
     * @param node 响应节点
     * @return result 节点（可能为 null）
     */
    private JsonNode unwrap(JsonNode node) {
        if (node.has("error")) {
            JsonNode err = node.get("error");
            String msg = err.has("message") ? err.get("message").asText() : "unknown";
            throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP 错误：" + msg);
        }
        return node.get("result");
    }

    /**
     * 从 MCP 工具定义解析风险级别（M10/T3，§3.2 统一工具契约）。
     *
     * <p>优先级：{@code destructiveHint=true} → L2（不可逆，最危险优先）；{@code readOnlyHint=true} → L0；
     * 协议未声明任何 annotations → 默认 L1（宁严，§7.3 前置防御）。
     *
     * @param tool tools/list 返回的单个工具 JSON 节点
     * @return 解析出的风险级别（默认 L1）
     */
    private static RiskLevel parseRiskLevel(JsonNode tool) {
        JsonNode annotations = tool == null ? null : tool.get("annotations");
        if (annotations != null && annotations.isObject()) {
            // 破坏性优先于只读：两者同时声明时以更危险的 L2 为准（§7.3 宁严）
            if (annotations.path("destructiveHint").asBoolean(false)) {
                return RiskLevel.L2_IRREVERSIBLE;
            }
            if (annotations.path("readOnlyHint").asBoolean(false)) {
                return RiskLevel.L0_READONLY_SAFE;
            }
        }
        return RiskLevel.L1_WRITE_REVERSIBLE;
    }

    /**
     * MCP 传输层抽象（连接/发现/执行/关闭）。两个实现：HTTP（Streamable HTTP）与 STDIO（子进程）。
     */
    private interface McpTransport {
        /** 建立连接并完成 initialize 握手。 */
        void initialize();

        /** 列出全部工具。 */
        List<ToolDefinition> listTools();

        /** 调用某个工具，返回结果文本。 */
        String callTool(String toolName, String argumentsJson);

        /** 关闭连接。 */
        void close();
    }

    /**
     * Streamable HTTP 传输层（M7/T2，§4.8 复用 OkHttp 单例）。
     *
     * <p>大白话：把 MCP 的 JSON-RPC 请求 POST 到对方地址。响应可以是普通 JSON，也可以是一串
     * SSE（text/event-stream）事件；本实现两种都认（按 content-type 分流）。
     */
    private class HttpTransport implements McpTransport {
        private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

        private final String endpoint;
        private final OkHttpClient callClient;
        /** 鉴权头集合（M10/T1）：来自 server 的 authType/authConfig，可能为空（NONE/免鉴权）。 */
        private final Map<String, String> authHeaders;
        /** 本 Server 的数据敏感度（M10/T4）：旗下所有工具统一标注，来自 server.getDataSensitivity()。 */
        private final DataSensitivity serverDataSensitivity;
        private final java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(1);

        HttpTransport(OkHttpClient baseClient, String endpoint, int connectTimeoutMs, int readTimeoutMs,
                      Map<String, String> authHeaders, DataSensitivity serverDataSensitivity) {
            this.endpoint = endpoint;
            this.authHeaders = authHeaders;
            this.serverDataSensitivity = serverDataSensitivity;
            // 基于共享单例派生，仅覆盖超时；连接池被复用（§4.8 复用 + §4.3 两级超时）
            this.callClient = baseClient.newBuilder()
                    .connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();
        }

        @Override
        public void initialize() {
            rpc("initialize", buildInitParams());
            sendNotification("notifications/initialized");
        }

        @Override
        public List<ToolDefinition> listTools() {
            JsonNode result = rpc("tools/list", objectMapper.createObjectNode());
            JsonNode tools = result == null ? null : result.get("tools");
            List<ToolDefinition> list = new ArrayList<>();
            if (tools != null && tools.isArray()) {
                for (JsonNode t : tools) {
                    String name = t.path("name").asText();
                    String description = t.path("description").asText();
                    JsonNode schema = t.get("inputSchema");
                    list.add(new ToolDefinition(name, description, schema, parseRiskLevel(t), serverDataSensitivity));
                }
            }
            return list;
        }

        @Override
        public String callTool(String toolName, String argumentsJson) {
            var params = objectMapper.createObjectNode();
            params.put("name", toolName);
            JsonNode args = (argumentsJson == null || argumentsJson.isBlank())
                    ? objectMapper.createObjectNode()
                    : readTree(argumentsJson);
            params.set("arguments", args);
            JsonNode result = rpc("tools/call", params);
            if (result != null && result.has("isError") && result.get("isError").asBoolean()) {
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "工具执行失败：" + result);
            }
            JsonNode content = result == null ? null : result.get("content");
            if (content != null && content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : content) {
                    if ("text".equals(c.path("type").asText()) && c.has("text")) {
                        sb.append(c.get("text").asText());
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
            return result == null ? "" : result.toString();
        }

        @Override
        public void close() {
            // 复用共享连接池，无需关闭 callClient（其连接池来自共享单例）
        }

        /**
         * 发一次 JSON-RPC 请求并拿回 result（按 id 关联）。
         *
         * @param method 方法名
         * @param params 参数（可为 null）
         * @return result 节点
         */
        private JsonNode rpc(String method, JsonNode params) {
            long id = idSeq.getAndIncrement();
            var req = objectMapper.createObjectNode();
            req.put("jsonrpc", JSONRPC);
            req.put("id", id);
            req.put("method", method);
            if (params != null) {
                req.set("params", params);
            }
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Accept", "application/json, text/event-stream");
            // 注入鉴权头（M10/T1）：Bearer/APIKey/自定义头，覆盖 initialize/list/call 全部请求
            applyAuthHeaders(builder, authHeaders);
            Request request = builder.post(RequestBody.create(req.toString(), JSON)).build();
            try (Response response = callClient.newCall(request).execute()) {
                int code = response.code();
                String body = response.body() != null ? response.body().string() : "";
                if (code >= 400) {
                    throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP 服务端返回 HTTP " + code);
                }
                if (body.isBlank()) {
                    return null; // notification 的 202 等空响应，无需解析
                }
                return parseRpc(body, id);
            } catch (BizException e) {
                throw e;
            } catch (IOException e) {
                // 连接/读超时（SocketTimeoutException 是它的子类）都在这里被捕获并转业务异常（§4.3 超时受控）
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP HTTP 调用异常：" + e.getMessage());
            }
        }

        /**
         * 发一次 JSON-RPC 通知（无 id、不关心响应），如 notifications/initialized。
         *
         * @param method 通知方法名
         */
        private void sendNotification(String method) {
            var req = objectMapper.createObjectNode();
            req.put("jsonrpc", JSONRPC);
            req.put("method", method);
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Accept", "application/json, text/event-stream");
            // 通知同样携带鉴权头（避免 initialize 后首个通知被鉴权网关拦截）
            applyAuthHeaders(builder, authHeaders);
            Request request = builder.post(RequestBody.create(req.toString(), JSON)).build();
            try (Response response = callClient.newCall(request).execute()) {
                // 通知无需解析响应体（§7.3 规则10：显式忽略而非吞异常）
                response.body();
            } catch (IOException e) {
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP 通知发送失败：" + e.getMessage());
            }
        }

        /**
         * 把鉴权头集合追加到请求构造器（M10/T1）。空集合直接跳过，不抛异常。
         *
         * @param builder 请求构造器
         * @param headers 鉴权头（可能为空）
         */
        private static void applyAuthHeaders(Request.Builder builder, Map<String, String> headers) {
            if (headers != null) {
                headers.forEach(builder::addHeader);
            }
        }

        /**
         * 解析 JSON-RPC 响应，兼容 application/json 与 text/event-stream（SSE）两种形态。
         *
         * @param raw       原始响应文本
         * @param expectedId 期望关联的请求 id
         * @return result 节点
         */
        private JsonNode parseRpc(String raw, long expectedId) {
            String text = raw.trim();
            if (text.contains("data:")) {
                // SSE：按行找 data: 负载，匹配 id
                for (String line : text.split("\\R")) {
                    String l = line.trim();
                    if (l.startsWith("data:")) {
                        String payload = l.substring(5).trim();
                        if (!payload.isEmpty()) {
                            JsonNode node = readTree(payload);
                            JsonNode idNode = node.get("id");
                            if (idNode != null && idNode.asLong() == expectedId) {
                                return unwrap(node);
                            }
                        }
                    }
                }
                throw new BizException(ErrorCode.MCP_CALL_FAILED,
                        "MCP SSE 响应未找到匹配 id=" + expectedId + " 的结果");
            }
            return unwrap(readTree(text));
        }
    }

    /**
     * STDIO 传输层（M7/T2）：拉起 MCP Server 子进程，用标准输入/输出传 JSON-RPC（行分隔）。
     *
     * <p>大白话：有些 MCP Server 是个本地命令行程序（如 npx 起的某个服务）。我们把它当子进程拉起来，
     * 往它的 stdin 写一行 JSON 请求，从它的 stdout 读一行 JSON 响应。
     */
    private class StdioTransport implements McpTransport {
        private final String command;
        private final DataSensitivity serverDataSensitivity;
        private Process process;
        private BufferedWriter stdin;
        private BufferedReader stdout;
        private final java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(1);

        StdioTransport(String command, DataSensitivity serverDataSensitivity) {
            this.command = command;
            this.serverDataSensitivity = serverDataSensitivity;
        }

        @Override
        public void initialize() {
            String[] cmd = command.trim().split("\\s+");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD); // 丢弃 stderr，避免子进程 stderr 缓冲打满导致阻塞
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "启动 MCP 子进程失败：" + e.getMessage());
            }
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            rpc("initialize", buildInitParams());
            sendNotification("notifications/initialized");
        }

        @Override
        public List<ToolDefinition> listTools() {
            JsonNode result = rpc("tools/list", objectMapper.createObjectNode());
            JsonNode tools = result == null ? null : result.get("tools");
            List<ToolDefinition> list = new ArrayList<>();
            if (tools != null && tools.isArray()) {
                for (JsonNode t : tools) {
                    list.add(new ToolDefinition(t.path("name").asText(), t.path("description").asText(), t.get("inputSchema"), parseRiskLevel(t), serverDataSensitivity));
                }
            }
            return list;
        }

        @Override
        public String callTool(String toolName, String argumentsJson) {
            var params = objectMapper.createObjectNode();
            params.put("name", toolName);
            JsonNode args = (argumentsJson == null || argumentsJson.isBlank())
                    ? objectMapper.createObjectNode()
                    : readTree(argumentsJson);
            params.set("arguments", args);
            JsonNode result = rpc("tools/call", params);
            if (result != null && result.has("isError") && result.get("isError").asBoolean()) {
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "工具执行失败：" + result);
            }
            JsonNode content = result == null ? null : result.get("content");
            if (content != null && content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : content) {
                    if ("text".equals(c.path("type").asText()) && c.has("text")) {
                        sb.append(c.get("text").asText());
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
            return result == null ? "" : result.toString();
        }

        @Override
        public void close() {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        /**
         * 写一行 JSON 请求并读回匹配 id 的响应（跳过非 JSON / 非匹配行，如服务端日志）。
         *
         * @param method 方法名
         * @param params 参数
         * @return result 节点
         */
        private JsonNode rpc(String method, JsonNode params) {
            long id = idSeq.getAndIncrement();
            var req = objectMapper.createObjectNode();
            req.put("jsonrpc", JSONRPC);
            req.put("id", id);
            req.put("method", method);
            if (params != null) {
                req.set("params", params);
            }
            writeLine(req.toString());
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode idNode = node.get("id");
                        if (idNode != null && idNode.asLong() == id) {
                            return unwrap(node);
                        }
                    } catch (IOException e) {
                        // 跳过非 JSON 行（服务端可能打印日志），继续读下一行；记 DEBUG 备查（§7.3 规则10 不空吞）
                        log.debug("mcp.stdio skip non-json line: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP STDIO 读取响应失败：" + e.getMessage());
            }
            throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP STDIO 连接断开，未收到响应 id=" + id);
        }

        /**
         * 写一行 JSON-RPC 通知（无 id、不读响应）。
         *
         * @param method 通知方法名
         */
        private void sendNotification(String method) {
            var req = objectMapper.createObjectNode();
            req.put("jsonrpc", JSONRPC);
            req.put("method", method);
            writeLine(req.toString());
        }

        /**
         * 写一行并 flush（§7.4 规则16 用缓冲，但必须 flush 才真正发出去）。
         *
         * @param line JSON 行
         */
        private void writeLine(String line) {
            try {
                stdin.write(line);
                stdin.write("\n");
                stdin.flush();
            } catch (IOException e) {
                throw new BizException(ErrorCode.MCP_CALL_FAILED, "MCP STDIO 写入请求失败：" + e.getMessage());
            }
        }
    }
}

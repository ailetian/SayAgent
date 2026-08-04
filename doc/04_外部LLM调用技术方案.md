# Hify 外部 LLM 调用技术方案

> 背景：Hify 需调用多个外部 LLM API（OpenAI / Claude / Gemini / Ollama），这些调用慢且不稳定。
> 目标：从线程管理、容错、超时、重试四个维度给出完整技术方案，支撑 50 人 → 几千人扩缩。
> 文档版本：v1.0
> 日期：2026-07-19
> 关联文档：《01_功能需求文档.md》《02_技术选型文档.md》《03_功能场景通俗说明.md》
> 技术栈：Spring Boot 单体 + Resilience4j + LangChain4j / WebClient

---

## 1. 总体调用管线（顺序很重要）

```
请求 → [限流 RateLimiter] → [舱壁 Bulkhead/独立线程池] → [熔断 CircuitBreaker]
     → [重试 Retry+退避] → [超时 TimeLimiter] → 实际调提供商
失败/熔断 → [降级 Fallback 切备用提供商]
```

任一环失败都触发 Fallback，而不是把请求线程卡死。

---

## 2. 线程管理（Thread Management）

**核心问题**：LLM 调用是阻塞 I/O，常需几秒到几十秒。若占用 Tomcat 请求线程，几十个并发就堵死全站。

**方案（三选一，按推荐度）**：

- **首选：Java 21 虚拟线程**（最接近 asyncio，阻塞代码也能高并发；Java 21 已正式支持）。
  ```yaml
  # application.yml
  spring:
    threads:
      virtual:
        enabled: true   # Tomcat 与 @Async 自动用虚拟线程
  ```
  写普通阻塞代码即可，JVM 用廉价虚拟线程承载，无需改写法。

- **备选（Java 21，关闭虚拟线程时）：专用线程池 + @Async**，且**按职责分池**，避免互相挤占：
  ```java
  @Bean("llmExecutor") Executor llmExecutor() {
      ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
      e.setCorePoolSize(20);
      e.setMaxPoolSize(100);
      e.setQueueCapacity(200);
      e.setRejectedExecutionHandler(new ThreadPoolRejectPolicy("降级返回"));
      return e;
  }
  ```
  建议拆 3 个池：LLM 生成、Embedding、RAG 检索，互不阻塞（防头阻塞）。

- **底层 HTTP 用 WebClient（非阻塞）** 而非 RestTemplate；LangChain4j 支持自定义非阻塞客户端。

**队列与背压**：线程池队列要**有界**（如 200），满了走拒绝策略（直接降级/排队），防止高负载 OOM。

> 对应 Python：虚拟线程 ≈ asyncio（廉价并发）；专用线程池 ≈ ThreadPoolExecutor；有界队列 ≈ 限流背压。

---

## 3. 超时（Timeout）

**两级超时，必须都设**：

- **HTTP 层**：连接超时 + 读取超时，按提供商不同定（Ollama 本地快、云端长）：
  ```yaml
  hify:
    providers:
      openai:  { connect-ms: 3000, read-ms: 60000 }
      claude:  { connect-ms: 3000, read-ms: 90000 }
      gemini:  { connect-ms: 3000, read-ms: 90000 }
      ollama:  { connect-ms: 1000, read-ms: 30000 }
  ```
  LangChain4j / OkHttp / WebClient 均支持在客户端层面设置。

- **应用层（整体 SLA）**：用 `TimeLimiter` 给一次"完整调用（含重试）"设上限，超时即中断并走 Fallback：
  ```java
  timeLimiter.executeCompletionStage(() -> callProvider(req));
  // 或 CompletableFuture.orTimeout(60, SECONDS)
  ```

- **流式输出**：设"两次 chunk 之间最大间隔"，超过即断流，前端显示"已中断/降级"。

---

## 4. 重试（Retry）

用 Resilience4j Retry（或 Spring Retry），要点：

| 要点 | 做法 |
|---|---|
| 退避 | 指数退避 + 抖动（避免惊群）：`waitDuration=1s, maxAttempts=3` |
| 只重试可恢复错误 | 429/502/503/504/超时/连接异常才重试；**400/401/403 永不重试**（永久错误） |
| 尊重 429 | 读 `Retry-After` 头决定下次等待 |
| 不超总预算 | 重试受 TimeLimiter 约束，整体超时剩余时间不够就不再重试 |
| 幂等 | 生成类 POST 用请求幂等键（idempotency key），避免重试重复计费/重复写 |

```java
@Retry(name = "llmProvider", fallbackMethod = "fallback")
public String chat(ChatReq req) { ... }
```

---

## 5. 容错（Fault Tolerance）

用 Resilience4j 四件套 + Fallback：

1. **熔断 CircuitBreaker（每提供商一个）**：连续失败达阈值就"开路"，直接拒绝并走备用；半开状态试探恢复。
   ```yaml
   resilience4j.circuitbreaker:
     instances:
       openai: { failureRateThreshold: 50, waitDurationInOpenState: 30s, slidingWindowType: COUNT, slidingWindowSize: 10 }
   ```

2. **舱壁 Bulkhead（即上面的独立线程池）**：隔离各提供商，OpenAI 慢不会耗尽 Claude 的额度。

3. **降级 Fallback（切备用提供商）**：按 Agent 配置的优先级链 `OpenAI → Claude → Gemini → Ollama`，当前不可用自动下一个。

4. **限流 RateLimiter**：保护提供商配额（防 429）也保护自身容量。

5. **优雅降级**：全部不可用 → 返回缓存答案/部分结果/"服务暂时降级"提示，而非 500。

6. **健康探测**：定时轻量 ping 各提供商，提前感知故障、辅助熔断恢复。

---

## 6. 组件设计（贴合 Modulith 包结构）

```
modelprovider/
  ├── ProviderClient           // 统一接口: chat()/embed()/health()
  ├── OpenAiClient / ClaudeClient / GeminiClient / OllamaClient
  ├── ResilienceDecorator      // 包 CircuitBreaker+Retry+TimeLimiter+Bulkhead
  └── ProviderRouter           // 按 Agent 配置 + 熔断状态选主/备提供商
conversation/
  └── ConversationService      // 调 ProviderRouter，拿结果流式返回
```

- 每提供商一个 `CircuitBreaker` 实例（配置在 yml）。
- `ProviderRouter` 读 Agent 的提供商优先级 + 各 CB 状态，做 Fallback 选择。
- 配置全在 `application.yml`，加新提供商零改代码。

---

## 7. SSE 流式响应（无需引入 WebFlux）

**结论：用 Spring MVC 原生的 `SseEmitter` 即可，不引入 WebFlux。** `SseEmitter` 底层基于 Servlet 3.0 异步，非响应式；对"阻塞型 LLM 调用 + 单人 + Java 新手"是最简单稳妥的选择。

### 7.1 实现方式
控制器方法返回 `SseEmitter` 后**立即释放 Tomcat 线程**，token 推送到专用线程池（`llmExecutor`）执行，与第 2 节线程管理方案一致：

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestBody ChatReq req) {
    SseEmitter emitter = new SseEmitter(120_000L);   // 整体超时（需 ≥ TimeLimiter 预算）

    llmExecutor.execute(() -> {                       // 丢到专用线程池，释放 Tomcat 线程
        try {
            providerRouter.stream(req, token -> {     // 拿逐 token 流
                try { emitter.send(SseEmitter.event().data(token)); }
                catch (IOException e) { emitter.completeWithError(e); }
            });
            emitter.complete();
        } catch (Exception e) {
            emitter.send(SseEmitter.event().name("error").data("服务降级"));
            emitter.complete();
        }
    });

    // 客户端断开时取消 LLM 调用，避免白烧 token / 线程泄漏
    emitter.onTimeout(emitter::complete);
    emitter.onCompletion(() -> providerRouter.cancel(req));
    return emitter;
}
```

> 配合 LangChain4j：其 `StreamingChatLanguageModel` / `TokenStream` 提供逐 token 回调，桥接到 `emitter.send(...)` 即可。

### 7.2 为什么不引入 WebFlux
| 维度 | MVC + SseEmitter（推荐） | WebFlux + Flux<ServerSentEvent> |
|---|---|---|
| 编程模型 | 同步风格，已熟悉 | Mono/Flux、背压，概念陡 |
| 与阻塞 LLM 调用配合 | 天然（丢线程池） | 须 `subscribeOn(Schedulers.boundedElastic())` 包裹，易堵 event loop |
| 学习成本（Java 新手） | 低 | 高 |
| 性能（50→几千人） | 异步足够，虚拟线程更佳 | 理论更高但本场景用不上 |
| 与现有栈 | 单体 MVC 一致 | 引入双模型易混乱 |

**说明**：WebFlux 价值在"全链路非阻塞高并发"，而本系统瓶颈是外部 LLM（本就慢、阻塞），用不到响应式；SseEmitter + 专用线程池已能腾出 Tomcat 线程，足够支撑。

### 7.3 注意点
- `SseEmitter` 构造函数超时（如 120s）为整体上限，须 ≥ `TimeLimiter` 预算，否则提前断流。
- 必须处理 `onCompletion` / `onTimeout` / `onError`，在客户端断开时**取消后台 LLM 调用**，否则持续耗 token。
- 前端：GET 用 `EventSource`；若需 POST 传复杂 body，用 `fetch` + `ReadableStream` 读取（SSE 文本格式照常解析）。

---

## 8. 规模提示（50 → 几千人）

- 调用层**无状态**，多实例水平扩容即可。
- 每提供商**独立熔断 + 独立线程池**，单点故障不扩散，扩容时不互相拖累。
- 热点（如对话引擎）将来可单独抽微服务，因边界已在包结构里画好。

---

*后续文档规划：《05_目录结构与依赖清单》（Maven 依赖 + Spring 包划分 + Vue 结构）。*

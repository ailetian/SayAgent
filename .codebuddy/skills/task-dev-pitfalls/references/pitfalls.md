# 任务开发坑位清单（hify_python）

只沉淀 CLAUDE.md 未覆盖的实战坑与项目特定陷阱。规范细节以 CLAUDE.md 为准，不重复。每条：`- 坑：...｜正：...｜来源：<任务> (<日期>)`。

---

## A. 规范合规（CLAUDE.md 已载规则，仅记"易漏的执行动作"）
- A1 建表/DDL 写完务必逐字段、逐索引对照 CLAUDE.md §6.1/§6.2 模板，并确保代码/计划验收点/规范三方一致（曾漏 `idx_created_at` 被退回）。｜来源：V2__user.sql (2026-07-22)
- A2 写完子任务逐条对齐 代码/计划验收点/规范——"测试绿 ≠ 合规"。｜来源：M3 T1 (2026-07-22)

## B. 工具/构建
- B1 批量 `write_to_file` 新建文件偶发被清空成 0 字节（报成功），后续报 `NoSuchBeanDefinition`/`找不到符号` 等离奇错（`AuthServiceImpl`、`AdminSeedRunner` 中招）。｜正：批量写完后对关键文件 `read_file` 确认非空；测试报 Bean 找不到但代码对，先疑文件被写空。｜来源：历史
- B2 `[WARNING] Null type safety`（如 `Collectors.toMap`/`filter`）多为改动前遗留，编译过即可，别误当自己引入的 bug。｜来源：M3 T6 (2026-07-23)
- B3 `replace_in_file` 偶发"返回成功但实际未落盘"（如给 record 加字段后编译仍报 `xxx() 未定义`，diff 看似成功）。｜正：关键编辑后 `read_file` 复核文件真的改了；单测报"方法未定义/找不到符号"但 diff 看似成功，先疑编辑未生效，重写一次即可。类似 B1 但要分别记。｜来源：M4 T3 (2026-07-23)

## C. 代码/架构（项目特定）
- C1 跨模块引用前用 `search_file` 确认真实包路径，别凭记忆（曾误把 `AuthController` 当成在 `common/`，实际在 `user/`）。｜来源：M3 T5 (2026-07-23)
- C2 角色 claim 为 `ROLE_ADMIN`（由 `AuthFilter` 放入 `SecurityContext`）；敏感操作在服务层 `SecurityContextHolder` 比对 `ROLE_ADMIN` 兜底，非 ADMIN 抛 `FORBIDDEN(4030)`。控制器只挡未登录 401。｜来源：M3 T5 (2026-07-23)
- C3 新增 `ErrorCode` 前先 grep 现有定义复用；`MODEL_NOT_FOUND(1001)`/`FORBIDDEN(4030)` 已存在，勿重定义。｜来源：M3 T5 (2026-07-23)
- C4 `ResilienceConfig` 未配 `retry-exceptions`，故 429（表现 `BizException(LLM_CALL_FAILED)`）也会被重试。写重试单测用真实 `ResilienceDecorator`+`RetryRegistry.ofDefaults()`（max-attempts:3、wait-duration:500ms），断言最终成功且仅调用 2 次（约 0.5s 正常）。｜来源：M3 T7 (2026-07-23)
- C5 删除走 `BaseRepository` `@SQLDelete` 软删，勿手写 `DELETE`；删默认模型抛 `FORBIDDEN`（路由不能无主）；`setDefault` 先取消旧默认再置新。｜来源：M3 T5 (2026-07-23)
- C6 测试断言要用真实值（如 `secret-1`）验证其不在序列化 JSON 中，别写 `"sk-123".replace(...)` 这类与真实值无关的无效断言。｜来源：M3 T5 (2026-07-23)
- C7 M5 T4/T5 计划文档用 `tenant_id` 描述检索隔离，但已合入的 T1 实际把隔离落在 `document_chunk.kb_id`（无 `tenant_id` 列、用 `seq` 而非 `chunk_index`）。开发 T4/T5 一律按已落库 schema 用 `kb_id` 对齐，RetrieveRequest 也加 `kbId`，别臆造 `tenant_id` 列/迁移（计划与已合入库表不一致时以库表为真相源）。｜来源：M5 T4/T5 (2026-07-24)
- C8 整模块 `mvn` 编译可能因 M4 `ProviderRouter.toConfig` 调用 `ModelProvider.getTimeoutMs()` 而失败——该字段从未实现、也无 Flyway 迁移。解锁：能力本就缺失（原代码 `getTimeoutMs() != null ? ... : 30000` 恒为 30000），按默认 `30000` 兜底等价；或正经补 `timeout_ms` 列 + 新迁移 V*。为验证别的 T* 而改 M4 逻辑时务必向用户点明，别静默改。｜来源：M5 T4/T5 构建 (2026-07-24)
- C9 测 `PgVectorRetrievalPort`（或任何 `JdbcTemplate.query`）用 `when(pgJdbcTemplate.query(any(),any(),any())).thenReturn(...)` 三参形式会编译失败：JdbcTemplate 同时有 `query(String,Object[],RowMapper)` 与 `query(String,RowMapper,Object...)` 两个重载都匹配（歧义）；且 assert 里若用到 `verify` 必须显式 `import static org.mockito.Mockito.verify;`（`@ExtendWith(MockitoExtension)` 不会自动带）。｜正：端口实际调用的是 5 参 `query(String,RowMapper,Object...)`，桩/verify 一律写 `query(any(), any(RowMapper.class), any(), any(), any())`；取真实调用参数用 `inv.getArgument(3)`（第 0=sql、1=RowMapper、2..=varargs，故 kb_id 在第 3 位），别用 `getArgument(2)` 当 Object[]。｜来源：M5 T6 (2026-07-24)

## D. 验证/交付
- D1 判定权归用户：保持进度表 ⬜ 待确认，不自报"通过"；建议用户跑真实环境校验（如 `curl /api/models`：未登录 401、USER 登录 POST 403、ADMIN 正常）。｜来源：M3 T5/T6/T7 (2026-07-23)
- D2 批量写完文件即 `mvn test -Dtest=...` 一次验证编译+逻辑，比分开省时。｜来源：M3 T5/T6/T7 (2026-07-23)

## E. M6 对话编排 / 异步落库（项目特定）
- E1 ConversationLogAsyncWriter 入参必须是强类型 DTO `LogRecord`，不是 `ConversationLog` 实体。T3 会先放占位 stub `saveAsync(ConversationLog entry)`（复用 sseExecutor）编译通过，T4 落地要整体替换为 `log(LogRecord)` 并接专用 `logExecutor`，否则验收点4（专用执行器、未复用 Tomcat/SSE 主线程）不达标且 conversation 模块越界构造日志实体。｜正：写 `LogWriteConfig` 提供 `@Bean("logExecutor")` 虚拟线程池，与 T2 `sseExecutor` 隔离；`log()` 只 `submit`+立即返回，persist 在后台线程 try/catch，单条失败仅 `log.error` 不抛（§8 异步落库）。｜来源：M6 T4 (2026-07-24)
- E2 LogRecord 字段须逐一对应 conversation_log 全部列（user_id/agent_id/conversation_id/question/in_tok/out_tok/provider/model/fallback）；agent_id/conversation_id 是 VARCHAR(50) 业务键（非 BIGINT，见 T1 验收点4/记忆 95075299）。LogRecord 绝不含 API key/token 密钥/密码（§7.11 规则37），token 仅指用量计数。｜来源：M6 T4 (2026-07-24)
- E3 集成 T4 到 T3：把 `ConversationService.writeLog` 里构造 ConversationLog 实体的代码改为构造 LogRecord 并 `conversationLogAsyncWriter.log(rec)`；同时把 `ConversationServiceTest` 的 `saveAsync(ConversationLog)` mock 改为 `log(LogRecord)` 并断言 LogRecord 字段，否则测试编译不过。｜来源：M6 T4 (2026-07-24)
- E4 PowerShell 跑 `mvn -o test "-Dtest=A,B"` 逗号必须放进引号内，否则 PowerShell 把逗号当数组分隔符报"缺少参量"。｜来源：M6 T4 (2026-07-24)

## F. K 系列 RAG 增强（项目特定）
- F1 String 字段存 JSON 列（如 kb.rag_config）不能用 `@JdbcTypeCode(SqlTypes.JSON)`：Hibernate 6 会把 String 交给 Jackson 再序列化一次，落库变成带引号转义的双重编码字符串。｜正：仿 agent 模块 `RefsJsonConverter` 写透传 `AttributeConverter<String,String>`（本项目已有 `knowledge/entity/JsonRawConverter`），`@Convert(converter=JsonRawConverter.class)` 直通读写。｜来源：K1 (2026-07-27)
- F2 K 计划文档写的 Flyway 版本号（V10/V11）会过时——mysql 实际已到 V18，必须先 `ls db/mysql/migration` 取真实最大版本再顺延（K1 实际用 V19/V20），并在进度表备注版本偏移，防后续任务照抄计划里的旧版本号冲突。｜来源：K1 (2026-07-27)
- F3 Maven 坏时的编译验证：复用 `F:/hify_tmp/k1_compile.sh`（fat jar 解压取 classpath + javac -parameters -encoding UTF-8，ASCII 临时路径），K 系列后续任务只需改脚本里的文件列表；javac 输出的"批注处理"中文提示是 Lombok 正常提醒，非错误。｜来源：K1 (2026-07-27)
- F4 Maven 坏时**跑单测**用 `F:/hify_tmp/k2_test.sh`（javac 全量 main → 编译测试 → 自写 `K2TestRunner` 经 junit-platform-launcher 启动；本地 .m2 无 console-standalone，必须自己写启动器）。｜坑：classpath 里拼 `$HOME/.m2/...` 会展开成 `/c/Users/...` POSIX 路径，**javac/java 在 Windows 认不出**，报「找不到符号 类 Test」，极易误判成缺 junit 依赖。｜正：`M2="$(cygpath -m "$HOME/.m2/repository")"`。｜来源：K2 (2026-08-03)
- F5 单测要读 classpath 上的 `application.yml`（如用 Binder 验证 yml 段真存在）时，jar 里那份是旧的——必须把 `src/main/resources/application.yml` 拷到 ASCII 临时目录并**排在 classpath 最前**，否则测的是旧配置、假绿。同理**改了 application.yml 也必须 `jar uf` 重建 target jar**（不只迁移 SQL），否则启动读旧配置，验收看到"改了没生效"。｜来源：K2 (2026-08-03)
- F6 测试方法名一律用**全英文**（`testMerge_invalidJson_returnsGlobal` 式）。中文标识符在本机 GBK 控制台 + javac 组合下有乱码/编译风险，不值得赌。｜来源：K2 (2026-08-03)
- F7 `vector_dim` 是**唯一禁止库级覆盖**的 RAG 参数：与 pgvector 列类型 `vector(1024)` 硬绑定，某库单独改维度只会写不进去或永远检索不出来。合并逻辑必须显式忽略并打 WARN（不是静默丢弃——要让人看见"你这条配置没生效"），并写回归用例把 snake/camel 两种写法都挡住。｜来源：K2 (2026-08-03)
- F8 pgvector 官方镜像**不带 zhparser**（`pg_available_extensions` 里都查不到，已装扩展只有 plpgsql/vector），所以 `V4__enable_zhparser` 即使 applied，探针实测仍 available=false → 降级 `simple`。这是本环境的**常态路径而非异常**：写 FTS 相关代码时默认按 simple 分词（中文按字切）设计兜底，别假设 zhparser 一定在；降级只改分词配置，**绝不关 `fts.enabled`**（不能把双路砍成单路）。｜来源：K2 (2026-08-03)
- F9 冒烟启动别抢 9095（常有上个任务的旧实例在跑），换 9096；关进程用 PowerShell `Get-NetTCPConnection -LocalPort 9096 -State Listen` 取 OwningProcess 再 `Stop-Process -Force`——Git Bash 的 `taskkill //PID` 会被 Windows 拒收，`cmd.exe /c` 被安全策略拦截。｜来源：K2 (2026-08-03)
- F10 引入 Tika 解析 PDF/DOCX 时本机 Maven 已坏、fat jar/.m2 均无 Tika，需从 Maven Central 下载 `tika-app` 自包含包（一个依赖覆盖 PDF+DOCX）作本地 classpath；但 tika-app 自带 `log4j-slf4j2-impl` 会与 fat jar 的 `log4j-to-slf4j` 冲突，导致 `RagConfig`/`Tika` 静态初始化抛「log4j-slf4j2-impl cannot be present with log4j-to-slf4j」。｜正：用 Python `zipfile` 生成精简版（剔除 `org/apache/logging/slf4j/` 绑定类 + `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`），Tika 改走应用 logback；K 系列解析/切片单测复用 `F:/hify_tmp/k3/k3_test.sh`（javac 全量 main + 自写 `K3TestRunner` 经 junit-platform-launcher 实跑，tika-slim 置于 classpath，运行期置前、编译期置后）。PDF 加密夹具：`StandardProtectionPolicy` 构造签名是 `(String owner, String user, AccessPermission)`，用 `new AccessPermission()`；PDFBox `showText` 写中文需非 WinAnsi 字体，夹具用纯 ASCII 避免 `U+xxxx is not available in font Helvetica`。｜来源：K3 (2026-08-04)
- F11 混合检索等需要 `IN (:list)` 动态占位的场景，若用 `JdbcTemplate.query(String, RowMapper, Object...)` 这种**变参**方法，Mockito strict stubbing 下 stub 的 matcher（如 `any(),any(),any(),any(),any()`）是按"固定 5 参"写的，但真实调用会把变参 `Object...` 展开成不定长实际参数，stub 的 arity 与实际调用对不上 → `PotentialStubbingProblem`（首跑一堆测试报 stub 不匹配，极易误判成 SQL/逻辑错）。｜正：改用 `NamedParameterJdbcTemplate.query(String, Map, RowMapper)`（**固定 3 参** + `IN (:list)` 由 Spring 自动按列表长度展开），arity 恒定、测试直接 stub 即可，同时天然满足"命名参数防注入"（§7.2）；记得在配置类注册 `NamedParameterJdbcTemplate` bean（构建在既有 `JdbcTemplate` 上，如 `PgDataSourceConfig` 的 `pgNamedJdbcTemplate`）。｜来源：K4 (2026-08-04)

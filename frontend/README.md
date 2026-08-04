# Hify 前端（Vue 3 + Vite）

## 运行

```bash
cd frontend
npm install
npm run dev      # 开发服务器 http://localhost:6177
```

构建产物：

```bash
npm run build    # 输出到 dist/
npm run preview  # 本地预览构建结果
```

## 与后端联调

- 开发期 `vite.config.js` 已将 `/api` 代理到后端 `http://localhost:9095`
  （端口取自 `backend/src/main/resources/application.yml` 的 `server.port`）。
- 后端需先启动（如 `java -jar backend/target/xxx.jar` 或 `mvn spring-boot:run`）。

## 目录结构（遵循 CLAUDE.md §3.3）

```
src/
├── main.js / App.vue        # 入口 + 基础布局（侧边/顶栏）
├── router/index.js          # /login /chat /agents /knowledge /models + 登录守卫
├── stores/                  # Pinia: auth.js（登录态）、app.js（UI 状态）
├── api/auth.js              # 登录接口封装
├── views/                   # Login.vue + 各页占位（后续 F3~F5 接管）
└── utils/                   # request.js（axios 封装）、token.js（token 存取）
```

## 设计语言（F2 登录页）

登录页采用「运动诗学 / Field.io 式流体沉浸」风格（经花叔设计技能评审选定）：
- 暖近黑底 `#14110F` + 全屏青 `#5EEAD4` / 琥珀 `#FFB454` 粒子流场背景；
- 玻璃拟态卡片（`backdrop-filter` 模糊 + 半透明描边）；
- 主操作按钮青→琥珀渐变；错误用红字 `#FF7A6B` 内联提示。
后续页面（chat/agents/...）建议沿用该色板与「沉浸 + 克制」基调，保持视觉一致。

## 关键约定

- 登录态：`stores/auth` 保存 token + 用户信息，持久化到 `localStorage`，刷新后仍在。
- 请求：`utils/request.js` 自动注入 `Authorization: Bearer <token>`，统一解析
  `{code,data,message}`（`code!==0` 抛错提示）；401 清登录态并跳 `/login`。
- 安全：token 只放请求头或 `localStorage`，**不进 URL query**；响应不含 password。
- 分页：列表一律用 `nextCursor` keyset（§3.5），禁止页码/offset。
- 版本：vue@3 / vite@5 / pinia@2，未按需升级大版本（§3.4）。

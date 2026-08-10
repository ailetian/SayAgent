# SayAgent 前端设计语言（Design Tokens）

> 本文件是 **全站统一视觉基准**，由 `src/views/Login.vue`（经 huashu-design 技能选定「Field.io 式流体沉浸」方向）确立。
> 规则来源：`../AGENTS.md` §3.6。后续每个新页面（`src/views/*`）开发前**必须先调用 huashu-design 设计技能**，实现时严格套用下方令牌。

## 设计令牌

| 角色 | 取值 | 说明 |
|---|---|---|
| 背景底 | `#14110F` | 暖近黑。**禁用** `#0D1117` 深蓝底、禁用赛博霓虹 |
| 主色 A | `#5EEAD4` 青 | 聚焦边框、粒子 |
| 主色 B | `#FFB454` 琥珀 | 粒子、渐变另一端 |
| 主按钮 | `linear-gradient(100deg, #5EEAD4, #FFB454)`，文字 `#14110F` | 沉浸科技感 |
| 错误/警示 | `#FF7A6B` 内联红字 | 错误就地提示，不靠 toast |
| 卡片 | 玻璃拟态 `backdrop-filter: blur(18px)` + 半透明描边 + 柔和投影 | 浮于粒子背景 |
| 输入框 | `rgba(0,0,0,0.28)` 深色半透明 | 必须覆盖 autofill 白底 |
| 字体 | UI：Inter / PingFang SC / Microsoft YaHei；等宽：IBM Plex Mono | — |

## 关键代码片段（直接复用）

**根容器（暗色原生控件）**
```css
.login-page { /* 或页面根 */ color-scheme: dark; background: #14110f; }
```

**输入框容器**
```css
.field {
  display: flex; align-items: center; gap: 10px;
  background: rgba(0, 0, 0, 0.28);
  border: 1px solid rgba(245, 245, 240, 0.14);
  border-radius: 12px; padding: 0 14px;
}
.field:focus-within { border-color: #5eead4; }
```

**覆盖浏览器自动填充白底（缺一不可）**
```css
.field input:-webkit-autofill,
.field input:-webkit-autofill:hover,
.field input:-webkit-autofill:focus {
  -webkit-text-fill-color: #f5f5f0;
  -webkit-box-shadow: 0 0 0 1000px rgba(0, 0, 0, 0.28) inset;
  caret-color: #f5f5f0;
  transition: background-color 9999s ease-in-out 0s;
}
```

**主按钮**
```css
.submit {
  background: linear-gradient(100deg, #5eead4, #ffb454);
  color: #14110f; border: 0; border-radius: 12px; font-weight: 600;
}
```

## 粒子流场背景（登录页基准，其他页面可选）
全屏 `<canvas>` + 约 90 个缓慢漂浮粒子（青/琥珀双色），组件卸载时 `cancelAnimationFrame` 并移除 resize 监听。参考 `src/views/Login.vue` 的 `startCanvas()` 实现。

## 方向 Demo 参考
三套方向的可视化 Demo（A 流体沉浸 / B 实验排版 / C 精确极简）保留在仓库 `_temp/design-demos/`（`demo-A/B/C-*.html`），新页面选型时可先打开对照。

## 页面组件设计系统（F3–F6 已落地，直接复用）

> 所有页面共用 `src/styles/theme.css`（已 `import` 进 `main.js`）。Element Plus 已开启暗色主题（`dark/css-vars.css` + `html.dark`）。

**应用外壳 `App.vue`**：`.ambient` 环境光（青/琥珀径向渐变浮于 `#14110F`）→ 玻璃拟态侧边栏（logo + 导航 + 用户信息 + 退出）→ 顶栏（面包屑 + 当前页标题）→ `<router-view>`。

**复用类速查**
- `.page` 内容容器；`.page-head`（标题 + 操作）；`.page-title` / `.page-sub`
- `.glass` 玻璃卡；`.btn-grad`（渐变主按钮）/ `.btn-ghost`（描边次按钮）
- `.field` 暗色输入框（已含 autofill 覆盖）；`.form-grid`（两列表单）/ `.span-2` / `.form-label`
- `.data-table` 沉浸数据表（th 静音色、行 hover 微亮、`.row-actions` 右对齐操作）
- `.tag` / `.tag-a`(青) / `.tag-b`(琥珀) / `.tag-danger`(红) 徽章
- `.md` Markdown 渲染容器（代码块/列表/引用已着色）
- `.error-text` 红字错误、`.muted` 静音文字、`.link-a`/`.link-danger` 文字按钮

**表单/弹窗**：用 Element Plus `el-dialog` / `el-form` / `el-select` / `el-switch`（暗色已适配）；纯文本/数字字段用 `.field` 保持品牌一致。

**聊天页 `Chat.vue`**：左侧 `.chat-side` 会话列表 + 右侧 `.chat-main`（Agent 选择器 + `.chat-scroll` 消息流 + `.chat-input`）。SSE 用原生 `fetch` + `ReadableStream` 读取，按真实 `ChatEvent`（`event` 判别 + `content` 增量 + `done` 结束）解析，助手消息经 `markdown-it` 渲染。

> 注意：计划文档与真实后端存在偏差——登录页/SSE/列表契约见各页面验收报告，实现一律以真实后端代码为准。

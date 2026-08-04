# 修复 Windows 控制台中文乱码：把整条链路统一为 UTF-8
#
# 原理：
#   1) 测试日志由 logback 按 JVM 默认编码写出（已是 UTF-8）
#   2) surefire 把 fork 测试进程的输出转发给 Maven 主进程，再写到 stdout
#   3) PowerShell 用 [Console]::OutputEncoding 解码 stdout 字节后显示
# 在 Windows 上，光 `chcp 65001` 对新版 PowerShell / Windows Terminal 往往不生效，
# 必须显式设置 [Console]::OutputEncoding 才能让它以 UTF-8 解码。
# 同时用 MAVEN_OPTS 保证 Maven 主进程也以 UTF-8 写出，避免二次转码。

# 让 PowerShell 以 UTF-8 解码 mvn 的 stdout（这是消除乱码的关键）
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 保险：让 Maven 主进程与 fork 测试进程都以 UTF-8 写出日志
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"
chcp 65001 | Out-Null

# 默认跑这两个测试类；也可在调用时传参：  .\mvn-test.ps1 ModelProviderVOTest
$tests = if ($args.Count -gt 0) { $args -join ',' } else { 'ProviderRouterTest,ModelProviderVOTest' }

mvn test "-Dtest=$tests" -DfailIfNoTests=false

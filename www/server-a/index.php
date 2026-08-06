<?php
/**
 * MCP Test Server A — 数学/计算器工具
 * 传输方式：Streamable HTTP（单个 POST 端点，返回 JSON-RPC）
 * 部署：把 www/ 整个目录放到 Apache 文档根目录，确保已启用 PHP 即可。
 *       访问地址示例：http://<你的域名>/server-a/
 */

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Accept, Mcp-Session-Id, mcp-session-id');
header('Access-Control-Expose-Headers: Mcp-Session-Id, mcp-session-id');

define('SERVER_NAME', 'php-mcp-test-a');
define('SERVER_VERSION', '1.0.0');
define('LOG_FILE', __DIR__ . '/mcp.log');

function log_msg($msg) {
    $line = '[' . date('Y-m-d H:i:s') . '] ' . $msg . PHP_EOL;
    @file_put_contents(LOG_FILE, $line, FILE_APPEND);
}

function send_json($data, $sessionId = null) {
    if ($sessionId) {
        header('Mcp-Session-Id: ' . $sessionId);
    }
    header('Content-Type: application/json');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function error_response($id, $code, $message) {
    return [
        'jsonrpc' => '2.0',
        'id' => $id,
        'error' => ['code' => $code, 'message' => $message]
    ];
}

// 预检请求
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// GET 仅用于 SSE 流式（本最小实现未启用），返回 405 以提示正确用法
if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    http_response_code(405);
    header('Content-Type: application/json');
    echo json_encode(['error' => 'Method Not Allowed. 请使用 POST 调用 MCP Streamable HTTP 端点。']);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    exit;
}

$raw = file_get_contents('php://input');
log_msg('REQ ' . $raw);
$parsed = json_decode($raw, true);
if ($parsed === null) {
    http_response_code(400);
    send_json(error_response(null, -32700, 'Parse error'));
}

// 会话管理（宽松模式：没有 session 就新建，不强制校验）
$sessionId = $_SERVER['HTTP_MCP_SESSION_ID'] ?? '';
if (!$sessionId) {
    $sessionId = bin2hex(random_bytes(16));
}

// 兼容批量请求（数组）与单条请求
$isBatch = is_array($parsed) && array_keys($parsed) === range(0, count($parsed) - 1);
$requests = $isBatch ? $parsed : [$parsed];
$responses = [];

foreach ($requests as $req) {
    $id = $req['id'] ?? null;
    $method = $req['method'] ?? '';
    $params = $req['params'] ?? [];

    // 通知（无 id）只记录不回复
    if ($id === null) {
        log_msg('NOTIFY ' . $method);
        continue;
    }

    switch ($method) {
        case 'initialize':
            $clientProto = $params['protocolVersion'] ?? '2025-03-26';
            $supported = ['2025-03-26', '2024-11-05'];
            $proto = in_array($clientProto, $supported) ? $clientProto : '2025-03-26';
            $responses[] = [
                'jsonrpc' => '2.0',
                'id' => $id,
                'result' => [
                    'protocolVersion' => $proto,
                    'capabilities' => ['tools' => ['listChanged' => false]],
                    'serverInfo' => ['name' => SERVER_NAME, 'version' => SERVER_VERSION]
                ]
            ];
            break;
        case 'ping':
            $responses[] = ['jsonrpc' => '2.0', 'id' => $id, 'result' => (object)[]];
            break;
        case 'tools/list':
            $responses[] = ['jsonrpc' => '2.0', 'id' => $id, 'result' => ['tools' => math_tools()]];
            break;
        case 'tools/call':
            $responses[] = handle_math_tool($id, $params);
            break;
        default:
            $responses[] = error_response($id, -32601, 'Method not found: ' . $method);
    }
}

if (empty($responses)) {
    http_response_code(202); // 收到纯通知，无响应体
    exit;
}
if ($isBatch) {
    send_json($responses, $sessionId);
} else {
    send_json($responses[0], $sessionId);
}

/* ---------- 工具定义与执行 ---------- */

function math_tools() {
    $num = ['type' => 'number'];
    $base = ['type' => 'object', 'properties' => ['a' => $num, 'b' => $num], 'required' => ['a', 'b']];
    return [
        ['name' => 'add', 'description' => '两数相加 (a + b)', 'inputSchema' => $base],
        ['name' => 'subtract', 'description' => '两数相减 (a - b)', 'inputSchema' => $base],
        ['name' => 'multiply', 'description' => '两数相乘 (a * b)', 'inputSchema' => $base],
        ['name' => 'divide', 'description' => '两数相除 (a / b)', 'inputSchema' => $base],
    ];
}

function handle_math_tool($id, $params) {
    $name = $params['name'] ?? '';
    $args = $params['arguments'] ?? [];
    $isError = false;
    try {
        switch ($name) {
            case 'add':      $v = num($args, 'a') + num($args, 'b'); break;
            case 'subtract': $v = num($args, 'a') - num($args, 'b'); break;
            case 'multiply': $v = num($args, 'a') * num($args, 'b'); break;
            case 'divide':
                $b = num($args, 'b');
                if ($b == 0) throw new Exception('除数不能为 0');
                $v = num($args, 'a') / $b;
                break;
            default: throw new Exception('未知工具: ' . $name);
        }
        $result = (string)$v;
    } catch (Exception $e) {
        $isError = true;
        $result = $e->getMessage();
    }
    return [
        'jsonrpc' => '2.0',
        'id' => $id,
        'result' => [
            'content' => [['type' => 'text', 'text' => $result]],
            'isError' => $isError
        ]
    ];
}

function num($args, $k) {
    if (!isset($args[$k])) throw new Exception("缺少参数: $k");
    return floatval($args[$k]);
}

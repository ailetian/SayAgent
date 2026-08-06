<?php
/**
 * MCP Test Server B — 文本/工具类
 * 传输方式：Streamable HTTP（单个 POST 端点，返回 JSON-RPC）
 * 部署：把 www/ 整个目录放到 Apache 文档根目录，确保已启用 PHP 即可。
 *       访问地址示例：http://<你的域名>/server-b/
 */

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Accept, Mcp-Session-Id, mcp-session-id');
header('Access-Control-Expose-Headers: Mcp-Session-Id, mcp-session-id');

define('SERVER_NAME', 'php-mcp-test-b');
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

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

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

$sessionId = $_SERVER['HTTP_MCP_SESSION_ID'] ?? '';
if (!$sessionId) {
    $sessionId = bin2hex(random_bytes(16));
}

$isBatch = is_array($parsed) && array_keys($parsed) === range(0, count($parsed) - 1);
$requests = $isBatch ? $parsed : [$parsed];
$responses = [];

foreach ($requests as $req) {
    $id = $req['id'] ?? null;
    $method = $req['method'] ?? '';
    $params = $req['params'] ?? [];

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
            $responses[] = ['jsonrpc' => '2.0', 'id' => $id, 'result' => ['tools' => text_tools()]];
            break;
        case 'tools/call':
            $responses[] = handle_text_tool($id, $params);
            break;
        default:
            $responses[] = error_response($id, -32601, 'Method not found: ' . $method);
    }
}

if (empty($responses)) {
    http_response_code(202);
    exit;
}
if ($isBatch) {
    send_json($responses, $sessionId);
} else {
    send_json($responses[0], $sessionId);
}

/* ---------- 工具定义与执行 ---------- */

function text_tools() {
    $str = ['type' => 'string'];
    return [
        ['name' => 'greet', 'description' => '向某人打招呼', 'inputSchema' => ['type' => 'object', 'properties' => ['name' => $str], 'required' => ['name']]],
        ['name' => 'uppercase', 'description' => '把文本转为大写', 'inputSchema' => ['type' => 'object', 'properties' => ['text' => $str], 'required' => ['text']]],
        ['name' => 'reverse', 'description' => '反转字符串', 'inputSchema' => ['type' => 'object', 'properties' => ['text' => $str], 'required' => ['text']]],
        ['name' => 'word_count', 'description' => '统计文本单词数', 'inputSchema' => ['type' => 'object', 'properties' => ['text' => $str], 'required' => ['text']]],
        ['name' => 'current_time', 'description' => '返回服务器当前时间', 'inputSchema' => ['type' => 'object', 'properties' => (object)[]]],
    ];
}

function handle_text_tool($id, $params) {
    $name = $params['name'] ?? '';
    $args = $params['arguments'] ?? [];
    $isError = false;
    try {
        switch ($name) {
            case 'greet':       $v = 'Hello, ' . strval($args['name'] ?? 'stranger') . '!'; break;
            case 'uppercase':   $v = strtoupper(strval($args['text'] ?? '')); break;
            case 'reverse':     $v = strrev(strval($args['text'] ?? '')); break;
            case 'word_count':  $v = (string)str_word_count(strval($args['text'] ?? '')); break;
            case 'current_time':$v = date('Y-m-d H:i:s'); break;
            default: throw new Exception('未知工具: ' . $name);
        }
    } catch (Exception $e) {
        $isError = true;
        $v = $e->getMessage();
    }
    return [
        'jsonrpc' => '2.0',
        'id' => $id,
        'result' => [
            'content' => [['type' => 'text', 'text' => $v]],
            'isError' => $isError
        ]
    ];
}

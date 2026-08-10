import json, urllib.request, urllib.error

B = 'http://localhost:9095/api'

def req(m, p, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(B + p, data=data, method=m)
    r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(r, timeout=120) as x:
            return x.status, json.loads(x.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {'raw': e.read().decode()[:200]}

# 1) login
s, d = req('POST', '/auth/login', body={'username': 'admin', 'password': 'admin123'})
tok = d['data']['token']
print('[login] code=%s' % d.get('code'))

# 2) SSE stream
import http.client
conn = http.client.HTTPConnection('localhost', 9095, timeout=120)
body = json.dumps({'agentId': '132', 'message': '年假怎么请'}).encode()
conn.request('POST', '/api/chat/stream', body=body,
             headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok})
resp = conn.getresponse()
print('[stream] HTTP %s' % resp.status)
conv_id = None
steps_seen = []
tokens = 0
answer_parts = []
done_seen = False
while True:
    line = resp.fp.readline()
    if not line:
        break
    t = line.decode('utf-8', 'ignore').strip()
    if not t.startswith('data:'):
        continue
    payload = t[5:].strip()
    if not payload or payload == '[DONE]':
        continue
    try:
        ev = json.loads(payload)
    except Exception:
        continue
    et = ev.get('event')
    if et == 'meta':
        conv_id = ev.get('conversationId')
        print('  [meta] conversationId=%s' % conv_id)
    elif et == 'step':
        steps_seen.append((ev.get('kind'), ev.get('stepStatus'), ev.get('content')))
        print('  [step] %s/%s : %s' % (ev.get('kind'), ev.get('stepStatus'), ev.get('content')))
    elif et == 'token':
        tokens += 1
        answer_parts.append(ev.get('content') or '')
    elif et == 'done':
        done_seen = True
        print('  [done] in=%s out=%s provider=%s' % (ev.get('inTok'), ev.get('outTok'), ev.get('provider')))
        break
    elif et == 'error':
        print('  [error] %s' % ev.get('message'))
        break
conn.close()

print('\n=== steps count=%d, tokens=%d, done=%s ===' % (len(steps_seen), tokens, done_seen))
full = ''.join(answer_parts)
print('=== answer (first 200) ===\n%s' % full[:200])

# 3) bug#2 check: reload history, confirm assistant message persisted
if conv_id:
    s, d = req('GET', '/chat/%s/messages' % conv_id, tok)
    items = (d.get('data') or {}).get('items') or []
    print('\n=== history reload: %d messages ===' % len(items))
    for m in items:
        role = m.get('role')
        c = (m.get('content') or '')
        print('  - %s : %s' % (role, (c[:80] + ('…' if len(c) > 80 else ''))))
    asst = [m for m in items if str(m.get('role')).upper() == 'ASSISTANT']
    if asst:
        last = asst[-1]
        persisted = (last.get('content') or '').strip()
        print('\n[bug#2 check] assistant persisted content len=%d -> %s' %
              (len(persisted), 'OK 已持久化' if persisted else 'FAIL 仍为空(会消失)'))
    else:
        print('\n[bug#2 check] FAIL 无 assistant 消息')

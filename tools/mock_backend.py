#!/usr/bin/env python3
"""CI mock of the MAX secure backend. ZERO provider calls: deterministic replies,
each prefixed "MOCK" so screenshots can never be mistaken for live AI output.
Protocol mirrors backend/worker.js: GET /v1/health, POST /v1/chat (SSE data: {"t":...})."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COUNTER = {'n': 10000000}

def reply_for(body):
    msgs = body.get('messages') or []
    last = next((m for m in reversed(msgs) if m.get('role') == 'user'), {})
    text = str(last.get('content', ''))
    atts = body.get('attachments') or []
    if 'random 8 digit' in text:
        COUNTER['n'] += 1   # regenerate must produce a DIFFERENT number
        return 'MOCK number %08d' % COUNTER['n']
    if 'MANGO' in text:
        return 'MOCK MANGO'
    if 'PAPAYA' in text:
        return 'MOCK PAPAYA'
    if atts:
        return 'MOCK red'   # attachment actually arrived -> answer names the color
    return 'MOCK ack'

class H(BaseHTTPRequestHandler):
    def _sse(self, text):
        self.send_response(200)
        self.send_header('content-type', 'text/event-stream')
        self.end_headers()
        self.wfile.write(('data: {"t": %s}\n\n' % json.dumps(text)).encode())
        self.wfile.write(b'data: [DONE]\n\n')

    def do_GET(self):
        if self.path.startswith('/v1/health'):
            body = json.dumps({'ok': True, 'mock': True}).encode()
            self.send_response(200)
            self.send_header('content-type', 'application/json')
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if not self.path.startswith('/v1/chat'):
            self.send_response(404); self.end_headers(); return
        if not (self.headers.get('authorization') or '').startswith('Bearer '):
            self.send_response(401); self.end_headers(); return
        n = int(self.headers.get('content-length') or 0)
        body = json.loads(self.rfile.read(n) or b'{}')
        msgs = body.get('messages') or []
        last = next((m for m in reversed(msgs) if m.get('role') == 'user'), {})
        if 'TRIGGER429' in str(last.get('content', '')):
            # deterministic quota-style failure, same shape the real worker returns
            err = json.dumps({'error': 'gemini 429: {\"error\": {\"code\": 429, \"message\": \"You exceeded your current quota\"}} | provider Retry-After: 30'})
            self.send_response(502)
            self.send_header('content-type', 'application/json')
            self.end_headers()
            self.wfile.write(err.encode())
            return
        self._sse(reply_for(body))

    def log_message(self, *a):
        pass

ThreadingHTTPServer(('127.0.0.1', 8787), H).serve_forever()

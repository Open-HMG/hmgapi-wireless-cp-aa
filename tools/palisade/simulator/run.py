#!/usr/bin/env python3
"""Run the local navigation simulator, or replay a JSON scenario headlessly."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit

from backend import HERE, JavaBackend, LIMIT
from model import enrich
from scenario import loads


def simulate(backend, scenario):
    result = backend.replay(scenario)
    result['backendInfo'] = backend.describe()
    return enrich(result, scenario)


def handler_for(backend):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            if args and str(args[1] if len(args) > 1 else '').startswith('4'):
                super().log_message(fmt, *args)

        def reply(self, status, body, mime='application/json; charset=utf-8'):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=True, allow_nan=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'self'")
            self.end_headers()
            self.wfile.write(data)

        def trusted(self):
            host = self.headers.get('Host', '')
            allowed = {f'127.0.0.1:{self.server.server_port}', f'localhost:{self.server.server_port}'}
            origin = self.headers.get('Origin')
            return host in allowed and (origin is None or origin in {'http://' + h for h in allowed})

        def do_GET(self):
            if not self.trusted():
                self.reply(403, {'error': 'Use the local simulator URL'}); return
            path = urlsplit(self.path).path
            if path == '/api/config':
                self.reply(200, backend.describe()); return
            if path == '/api/scenarios':
                self.reply(200, [loads(p.read_text()) for p in sorted((HERE / 'scenarios').glob('*.json'))]); return
            files = {'/': ('index.html', 'text/html; charset=utf-8'),
                     '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
                     '/style.css': ('style.css', 'text/css; charset=utf-8')}
            if path not in files:
                self.reply(404, {'error': 'Not found'}); return
            name, mime = files[path]
            self.reply(200, (HERE / 'web' / name).read_bytes(), mime)

        def do_POST(self):
            if not self.trusted():
                self.reply(403, {'error': 'Only same-origin local requests are accepted'}); return
            if self.path != '/api/replay':
                self.reply(404, {'error': 'Not found'}); return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= LIMIT:
                    self.reply(413, {'error': 'Scenario must be between 1 byte and 1 MiB'}); return
                self.connection.settimeout(10)
                payload = self.rfile.read(length)
                scenario = loads(payload.decode('utf-8'))
                self.reply(200, simulate(backend, scenario))
            except (ValueError, UnicodeError) as exc:
                self.reply(400, {'error': str(exc)})
            except Exception as exc:
                self.reply(500, {'error': str(exc)})
    return Handler


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home', type=Path)
    p.add_argument('--port', type=int, default=8765)
    p.add_argument('--replay', type=Path, help='Replay one scenario and exit (nonzero on failed expectations)')
    p.add_argument('--output', type=Path, help='Write the replay result JSON instead of stdout')
    args = p.parse_args()
    backend = JavaBackend(args.java_home)
    try:
        if args.replay:
            if args.replay.stat().st_size > LIMIT: raise ValueError('Scenario exceeds 1 MiB')
            result = simulate(backend, loads(args.replay.read_text()))
            text = json.dumps(result, indent=2, ensure_ascii=True) + '\n'
            if args.output: args.output.write_text(text)
            else: print(text, end='')
            return 1 if result['summary']['failed'] else 0
        if args.output: p.error('--output requires --replay')
        server = ThreadingHTTPServer(('127.0.0.1', args.port), handler_for(backend))
        print(f'Palisade simulator: http://127.0.0.1:{server.server_port}', flush=True)
        print('Java production bridge · schematic displays · Ctrl-C to stop', flush=True)
        try: server.serve_forever()
        except KeyboardInterrupt: pass
        finally: server.server_close()
        return 0
    finally:
        backend.close()


if __name__ == '__main__':
    try: sys.exit(main())
    except (RuntimeError, ValueError, OSError) as exc: sys.exit(str(exc))

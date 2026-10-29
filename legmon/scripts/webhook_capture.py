#!/usr/bin/env python3
"""Tiny HTTP sink that captures the LegMon -> MonMon statement webhook payload to a file, then exits."""
import http.server
import sys

OUT = sys.argv[2]


class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        open(OUT, "wb").write(body)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok","statementId":0}')
        raise SystemExit(0)

    def log_message(self, *a):
        pass


http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()

"""A stand-in for llama-server, shaped like it where the proxy cares.

The real server sends nothing, not even response headers, until prefill has
produced the first token (tools/server/server-context.cpp in the pinned
llama.cpp, "the first error must be treated as non-stream response"). This
backend reproduces that silence and one other shape:

  POST /v1/slow?s=N   sleep N seconds, then a small JSON completion
  POST /v1/flood      a fast, very large chunked body, for stalled-reader tests

Usage: python3 -I fake_llama.py PORT
"""
import http.server
import sys
import time
import urllib.parse


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        self.rfile.read(int(self.headers.get("content-length", 0)))
        url = urllib.parse.urlparse(self.path)
        if url.path == "/v1/flood":
            self.send_response(200)
            self.send_header("content-type", "text/event-stream")
            self.send_header("transfer-encoding", "chunked")
            self.end_headers()
            chunk = b"data: " + b"x" * 65536 + b"\n\n"
            try:
                for _ in range(20000):
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                self.wfile.write(b"0\r\n\r\n")
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        delay = float(urllib.parse.parse_qs(url.query).get("s", ["0"])[0])
        time.sleep(delay)
        body = b'{"choices":[{"message":{"content":"done"}}]}'
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.send_header("connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()

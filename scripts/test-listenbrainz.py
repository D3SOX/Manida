#!/usr/bin/env python3
"""Loopback ListenBrainz contract fixture for native app tests."""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

listens = []
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/test/listens":
            with lock:
                self.respond(200, {"listens": list(listens)})
        elif self.path == "/1/validate-token":
            self.respond(200, {"valid": self.headers.get("Authorization") == "Token fixture-token", "user_name": "fixture"})
        else:
            self.respond(404, {})

    def do_DELETE(self):
        if self.path != "/test/listens":
            return self.respond(404, {})
        with lock:
            listens.clear()
        self.respond(200, {"status": "ok"})

    def do_POST(self):
        if self.path != "/1/submit-listens":
            return self.respond(404, {})
        if self.headers.get("Authorization") != "Token fixture-token":
            return self.respond(401, {})
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length <= 512 * 1024:
            return self.respond(400, {})
        try:
            payload = json.loads(self.rfile.read(length))
            if payload["listen_type"] not in ("single", "playing_now", "import") or not payload["payload"]:
                raise ValueError()
            for event in payload["payload"]:
                metadata = event["track_metadata"]
                if not metadata["artist_name"] or not metadata["track_name"]:
                    raise ValueError()
                if payload["listen_type"] != "playing_now" and not isinstance(event["listened_at"], int):
                    raise ValueError()
        except (ValueError, KeyError, TypeError):
            return self.respond(400, {})
        with lock:
            listens.append(payload)
        self.respond(200, {"status": "ok"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=14535)
    options = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", options.port), Handler).serve_forever()

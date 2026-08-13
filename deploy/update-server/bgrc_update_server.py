#!/usr/bin/env python3
"""Cloudflare Tunnel 전용, 서명된 업데이트 파일의 최소 정적 HTTP 서버."""

from __future__ import annotations

import argparse
import mimetypes
import re
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


RELEASE_FILE = re.compile(r"^attendance-\d+\.\d+\.\d+\.jar$")
STABLE_FILES = {"manifest.json", "manifest.json.sig"}


class UpdateFileHandler(SimpleHTTPRequestHandler):
    root_directory: Path

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(self.root_directory), **kwargs)

    def do_GET(self):
        if not self._is_allowed_path():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        super().do_GET()

    def do_HEAD(self):
        if not self._is_allowed_path():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        super().do_HEAD()

    def do_POST(self):
        self.send_error(HTTPStatus.METHOD_NOT_ALLOWED)

    do_PUT = do_POST
    do_DELETE = do_POST
    do_PATCH = do_POST

    def list_directory(self, path):
        self.send_error(HTTPStatus.NOT_FOUND)
        return None

    def end_headers(self):
        path = urlsplit(self.path).path
        if path.startswith("/stable/"):
            self.send_header("Cache-Control", "no-store")
        elif path.startswith("/releases/"):
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
        self.send_header("X-Content-Type-Options", "nosniff")
        super().end_headers()

    def guess_type(self, path):
        if path.endswith(".jar"):
            return "application/java-archive"
        return mimetypes.guess_type(path)[0] or "application/octet-stream"

    def _is_allowed_path(self) -> bool:
        path = unquote(urlsplit(self.path).path)
        if path.startswith("/stable/"):
            return path.removeprefix("/stable/") in STABLE_FILES
        if path.startswith("/releases/"):
            return bool(RELEASE_FILE.fullmatch(path.removeprefix("/releases/")))
        return False

    def log_message(self, format, *args):
        print("%s - %s" % (self.log_date_time_string(), format % args), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()

    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    UpdateFileHandler.root_directory = root
    server = ThreadingHTTPServer((args.bind, args.port), UpdateFileHandler)
    print(f"BGRC update server: http://{args.bind}:{args.port} ({root})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

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


# 릴리스는 major.minor.patch를 기본으로 하되, 1.2.9.4처럼 추가 숫자 버전도 허용한다.
RELEASE_FILE = re.compile(r"^attendance-\d+(?:\.\d+){2,}\.jar$")
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
        # 파일이 실제로 존재할 때만 장기 캐시한다. 없는 릴리스의 404가 Cloudflare에
        # 장기간 남으면, 이후 같은 버전 파일을 올려도 launcher가 받을 수 없게 된다.
        elif self._is_existing_release(path):
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

    def _is_existing_release(self, path: str) -> bool:
        if not path.startswith("/releases/"):
            return False
        filename = path.removeprefix("/releases/")
        return bool(RELEASE_FILE.fullmatch(filename) and (self.root_directory / "releases" / filename).is_file())

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

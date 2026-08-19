#!/usr/bin/env python3
"""电脑作为附近快传分享端；手机/电脑浏览器打开随机令牌地址即可下载。"""

from __future__ import annotations

import argparse
import hashlib
import html
import mimetypes
import secrets
import socket
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse


def local_ips() -> list[str]:
    values: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in values:
                values.append(ip)
    except Exception:
        pass
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("192.0.2.1", 9))
        ip = probe.getsockname()[0]
        probe.close()
        if ip not in values:
            values.insert(0, ip)
    except Exception:
        pass
    return values or ["127.0.0.1"]


def unique_names(paths: list[Path]) -> dict[str, Path]:
    output: dict[str, Path] = {}
    for path in paths:
        original = path.name or "shared.bin"
        candidate = original
        index = 2
        while candidate in output:
            candidate = f"{path.stem}-{index}{path.suffix}"
            index += 1
        output[candidate] = path
    return output


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    token = ""
    root: Path | None = None
    flat: dict[str, Path] = {}

    def log_message(self, fmt: str, *args) -> None:
        print(f"[{self.log_date_time_string()}] {fmt % args}")

    def do_HEAD(self) -> None:
        self._serve(False)

    def do_GET(self) -> None:
        self._serve(True)

    def _serve(self, body: bool) -> None:
        self.close_connection = True
        path = unquote(urlparse(self.path).path)
        prefix = f"/s/{self.token}"
        if path != prefix and not path.startswith(prefix + "/"):
            self.send_error(404)
            return
        relative = path[len(prefix) :].strip("/")
        if self.root is not None:
            target = (self.root / relative).resolve()
            try:
                target.relative_to(self.root)
            except ValueError:
                self.send_error(403)
                return
            if not target.exists():
                self.send_error(404)
            elif target.is_dir():
                self._directory(target, body)
            else:
                self._file(target, body)
            return
        if not relative:
            self._flat_index(body)
            return
        target = self.flat.get(relative)
        if target is None:
            self.send_error(404)
        else:
            self._file(target, body)

    def _directory(self, directory: Path, body: bool) -> None:
        rows: list[str] = []
        if self.root is not None and directory != self.root:
            rows.append("<li><a href='../'>📁 ../</a></li>")
        for child in sorted(directory.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower())):
            if child.name.startswith("."):
                continue
            suffix = "/" if child.is_dir() else ""
            label = ("📁 " if child.is_dir() else "") + html.escape(child.name) + suffix
            size = "" if child.is_dir() else f" · {child.stat().st_size:,} B"
            rows.append(f"<li><a href='{quote(child.name)}{suffix}'>{label}</a>{size}</li>")
        self._html("".join(rows), body)

    def _flat_index(self, body: bool) -> None:
        rows = "".join(
            f"<li><a href='{quote(name)}'>{html.escape(name)}</a> · {path.stat().st_size:,} B</li>"
            for name, path in sorted(self.flat.items())
        )
        self._html(rows, body)

    def _html(self, rows: str, body: bool) -> None:
        raw = (
            "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'>"
            "<meta name='referrer' content='no-referrer'><title>附近快传</title>"
            "<style>body{max-width:900px;margin:30px auto;padding:0 18px;font:16px system-ui;background:#101512;color:#edf5f1}"
            "a{color:#72ddb2;word-break:break-all}li{padding:9px 0;border-bottom:1px solid #28352f}</style>"
            "<h1>电脑附近快传</h1><p>只在当前局域网中有效；关闭终端后立即停止。</p><ul>"
            + rows
            + "</ul>"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(raw)

    def _file(self, path: Path, body: bool) -> None:
        stat = path.stat()
        total = stat.st_size
        etag = 'W/"' + hashlib.sha256(f"{path}|{total}|{stat.st_mtime_ns}".encode()).hexdigest()[:24] + '"'
        modified = formatdate(stat.st_mtime, usegmt=True)
        range_header = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        use_range = bool(range_header) and (not if_range or if_range in (etag, modified))
        start, end, status = 0, max(total - 1, 0), 200
        if use_range:
            try:
                unit, value = range_header.split("=", 1)
                if unit.lower() != "bytes" or "," in value:
                    raise ValueError
                left, right = value.split("-", 1)
                if not left:
                    length = int(right)
                    if length <= 0:
                        raise ValueError
                    start = max(total - length, 0)
                else:
                    start = int(left)
                end = int(right) if left and right else total - 1
                end = min(end, total - 1)
                if start < 0 or start >= total or end < start:
                    raise ValueError
                status = 206
            except Exception:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total}")
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                return
        length = 0 if total == 0 else end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", modified)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()
        if not body or length == 0:
            return
        with path.open("rb") as source:
            source.seek(start)
            remaining = length
            while remaining:
                chunk = source.read(min(128 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def main() -> int:
    parser = argparse.ArgumentParser(description="让电脑成为带临时令牌的附近快传分享端")
    parser.add_argument("paths", nargs="+", help="文件或单个文件夹")
    parser.add_argument("-p", "--port", type=int, default=8765)
    args = parser.parse_args()
    paths = [Path(value).expanduser().resolve() for value in args.paths]
    missing = [str(path) for path in paths if not path.exists()]
    if missing:
        parser.error("路径不存在：" + ", ".join(missing))
    Handler.token = secrets.token_urlsafe(24)
    if len(paths) == 1 and paths[0].is_dir():
        Handler.root = paths[0]
        Handler.flat = {}
    else:
        Handler.root = None
        expanded: list[Path] = []
        for path in paths:
            expanded.extend([path] if path.is_file() else [p for p in path.rglob("*") if p.is_file()])
        Handler.flat = unique_names(expanded)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print("分享中：")
    for ip in local_ips():
        print(f"  http://{ip}:{args.port}/s/{Handler.token}/")
    print("Ctrl+C 停止；地址中的随机令牌不要发给无关人员。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

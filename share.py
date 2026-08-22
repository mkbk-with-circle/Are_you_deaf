#!/usr/bin/env python3
"""电脑作为附近快传分享端；手机/电脑浏览器打开随机令牌地址即可下载。"""

from __future__ import annotations

import argparse
import hashlib
import html
import mimetypes
import os
import platform
import re
import secrets
import socket
import subprocess
import sys
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

# 客户端握手后立刻断开（热点网关探测、系统预检），不是真正访问失败
_HARMLESS_ERRNOS = {32, 54, 57, 104}

# conda 的 Python 未过 macOS 入站签名，热点里会 TCP 通但 HTTP 空回复
_CONDA_MARKERS = ("anaconda", "miniconda", "miniforge", "/opt/anaconda")


def _reexec_if_conda() -> None:
    # 只在 macOS 换解释器；Windows/Git Bash 里 /usr/bin/python3 是假路径，exec 后会静默退出
    if sys.platform != "darwin":
        return
    exe = (sys.executable or "").lower()
    if not any(marker in exe for marker in _CONDA_MARKERS):
        return
    for candidate in ("/opt/homebrew/bin/python3", "/usr/bin/python3"):
        if Path(candidate).exists():
            print(f"当前是 conda Python，macOS 会拦局域网入站，改用 {candidate}", flush=True)
            os.execv(candidate, [candidate, str(Path(__file__).resolve()), *sys.argv[1:]])
    print("警告：请用 /usr/bin/python3 或 Homebrew python3 启动，不要用 conda。", flush=True)


def _missing_hint(raw: str, resolved: Path) -> str:
    lines = [f"路径不存在：{raw}", f"  已解析成：{resolved}"]
    downloads = Path.home() / "Downloads"
    if downloads.exists() and resolved != downloads:
        lines.append(f"  本机下载目录是：{downloads}")
    if sys.platform == "win32":
        lines.append('  PowerShell 请写：python share.py "$HOME\\Downloads"')
        lines.append(r'  CMD 请写：       python share.py "%USERPROFILE%\Downloads"')
        lines.append(r'  或使用完整路径： python share.py "D:\某个文件夹"')
    return "\n".join(lines)


def _resolve_input_path(value: str) -> Path:
    """Expand both shell styles and tolerate an unconverted Git Bash /c/... argument."""
    expanded = os.path.expandvars(value)
    if sys.platform == "win32":
        msys = re.fullmatch(r"/([A-Za-z])(?:/(.*))?", expanded)
        if msys:
            rest = msys.group(2) or ""
            expanded = f"{msys.group(1).upper()}:/{rest}"
    return Path(expanded).expanduser().resolve()


def _is_wsl() -> bool:
    return sys.platform == "linux" and (
        bool(os.environ.get("WSL_DISTRO_NAME")) or "microsoft" in platform.release().lower()
    )

_VPN_IFACE = ("utun", "ipsec", "ppp", "tun", "wg", "wintun", "tap", "wireguard")


def _ifaces() -> list[tuple[str, str]]:
    """列出 (网卡, IPv4)，不把默认路由当成唯一地址。"""
    if sys.platform == "win32":
        return _ifaces_ipconfig()
    try:
        text = subprocess.check_output(["ifconfig"], text=True, errors="replace")
    except Exception:
        return _ifaces_ipconfig()
    iface = ""
    found: list[tuple[str, str]] = []
    seen: set[str] = set()
    for line in text.splitlines():
        if line and not line[0].isspace() and ":" in line:
            iface = line.split(":", 1)[0]
            continue
        parts = line.split()
        if "inet" not in parts:
            continue
        ip = parts[parts.index("inet") + 1]
        if ip.startswith("addr:"):
            ip = ip[5:]
        if "." not in ip or ip.startswith("127.") or ip.startswith("169.254.") or ip in seen:
            continue
        seen.add(ip)
        found.append((iface, ip))
    return found


def _ifaces_ipconfig() -> list[tuple[str, str]]:
    """Windows 用 ipconfig 列 IPv4。"""
    try:
        text = subprocess.check_output(["ipconfig"], text=True, errors="replace")
    except Exception:
        return []
    iface = "adapter"
    found: list[tuple[str, str]] = []
    seen: set[str] = set()
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.endswith(":") and ("adapter" in line.lower() or "适配器" in line):
            iface = stripped.rstrip(":").split()[-1]
            continue
        if "IPv4" not in stripped and "IP Address" not in stripped:
            continue
        ip = stripped.rsplit(":", 1)[-1].strip()
        if "." not in ip or ip.startswith("127.") or ip.startswith("169.254.") or ip in seen:
            continue
        seen.add(ip)
        found.append((iface, ip))
    return found


def _kind(iface: str, ip: str) -> str:
    name = iface.lower()
    if any(name.startswith(prefix) for prefix in _VPN_IFACE):
        return "vpn"
    if ip.startswith("198.18.") or ip.startswith("198.19."):
        return "vpn"
    return "lan"


def advertise_urls(port: int, token: str) -> None:
    rows = _ifaces()
    if not rows:
        rows = [("unknown", "127.0.0.1")]
    print("分享中。手机请用「热点/Wi-Fi」那一行；开着 VPN 时不要用 VPN 地址。")
    print("接收的手机如果也开了 VPN，先关掉，否则经常访问不了局域网。")
    for iface, ip in rows:
        kind = _kind(iface, ip)
        tag = "热点/Wi-Fi" if kind == "lan" else "VPN，手机通常打不开"
        print(f"  [{tag} · {iface}] http://{ip}:{port}/s/{token}/")
    print("用系统浏览器打开 http 地址（不要改成 https）。Ctrl+C 停止。", flush=True)


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
    sources: dict[str, Path] = {}

    def handle(self) -> None:
        try:
            super().handle()
        except OSError as exc:
            if getattr(exc, "errno", None) in _HARMLESS_ERRNOS:
                return
            raise

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
            self._from_base(self.root, relative, body)
            return
        if self.sources:
            if not relative:
                self._source_index(body)
                return
            head, _, rest = relative.partition("/")
            source = self.sources.get(head)
            if source is None:
                self.send_error(404)
                return
            if source.is_file():
                if rest:
                    self.send_error(404)
                else:
                    self._file(source, body)
                return
            self._from_base(source, rest, body, mount=source.resolve())
            return
        if not relative:
            self._flat_index(body)
            return
        target = self.flat.get(relative)
        if target is None:
            self.send_error(404)
        else:
            self._file(target, body)

    def _from_base(self, base: Path, relative: str, body: bool, mount: Path | None = None) -> None:
        target = (base / relative).resolve()
        try:
            target.relative_to(base.resolve())
        except ValueError:
            self.send_error(403)
            return
        if not target.exists():
            self.send_error(404)
        elif target.is_dir():
            self._directory(target, body, mount=mount)
        else:
            self._file(target, body)

    def _directory(self, directory: Path, body: bool, mount: Path | None = None) -> None:
        rows: list[str] = []
        if mount is not None or (self.root is not None and directory != self.root):
            rows.append("<li><a href='../'>📁 ../</a></li>")
        for child in sorted(directory.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower())):
            if child.name.startswith("."):
                continue
            suffix = "/" if child.is_dir() else ""
            label = ("📁 " if child.is_dir() else "") + html.escape(child.name) + suffix
            size = "" if child.is_dir() else f" · {child.stat().st_size:,} B"
            rows.append(f"<li><a href='{quote(child.name)}{suffix}'>{label}</a>{size}</li>")
        self._html("".join(rows), body)

    def _source_index(self, body: bool) -> None:
        rows: list[str] = []
        for name, path in sorted(self.sources.items(), key=lambda item: (item[1].is_file(), item[0].lower())):
            if path.is_dir():
                rows.append(f"<li><a href='{quote(name)}/'>{html.escape(name)}/</a></li>")
            else:
                rows.append(
                    f"<li><a href='{quote(name)}'>{html.escape(name)}</a> · {path.stat().st_size:,} B</li>"
                )
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


class Server(ThreadingHTTPServer):
    def handle_error(self, request, client_address) -> None:
        err = sys.exc_info()[1]
        if isinstance(err, OSError) and err.errno in _HARMLESS_ERRNOS:
            return
        super().handle_error(request, client_address)


def main() -> int:
    print(f"[share] Python {sys.version.split()[0]}  {sys.executable}", flush=True)
    _reexec_if_conda()
    if _is_wsl():
        print(
            "警告：当前是 WSL 的 Linux Python，不是 Windows Python；"
            "手机热点可能无法直接访问 WSL NAT。建议改用 PowerShell 或 Git Bash。",
            flush=True,
        )
    parser = argparse.ArgumentParser(description="让电脑成为带临时令牌的附近快传分享端")
    parser.add_argument("paths", nargs="+", help="一个或多个文件/文件夹")
    parser.add_argument("-p", "--port", type=int, default=8765)
    args = parser.parse_args()
    paths = [_resolve_input_path(value) for value in args.paths]
    missing = []
    for raw, path in zip(args.paths, paths):
        if not path.exists():
            print(_missing_hint(raw, path), flush=True)
            missing.append(str(path))
    if missing:
        return 2
    Handler.token = secrets.token_urlsafe(24)
    Handler.root = None
    Handler.flat = {}
    Handler.sources = {}
    if len(paths) == 1 and paths[0].is_dir():
        Handler.root = paths[0]
    elif all(path.is_file() for path in paths):
        Handler.flat = unique_names(paths)
    else:
        # 多个文件夹或文件+文件夹：首页列出各源，目录可继续往里走
        Handler.sources = unique_names(paths)
    print(f"共 {len(paths)} 个源", flush=True)
    server = Server(("0.0.0.0", args.port), Handler)
    advertise_urls(args.port, Handler.token)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as exc:
        print(f"启动失败：{exc}", flush=True)
        raise

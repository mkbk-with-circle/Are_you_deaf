#!/usr/bin/env python3
"""电脑作为附近快传分享端；手机/电脑浏览器打开随机令牌地址即可下载。"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import mimetypes
import os
import platform
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

# 客户端握手后立刻断开（热点网关探测、系统预检），不是真正访问失败
_HARMLESS_ERRNOS = {32, 54, 57, 104}

# conda 的 Python 未过 macOS 入站签名，热点里会 TCP 通但 HTTP 空回复
_CONDA_MARKERS = ("anaconda", "miniconda", "miniforge", "/opt/anaconda")

MAX_RECURSIVE_FILES = 10_000
MAX_ZIP_FILES = 10_000
MAX_ZIP_BYTES = 8 * 1024 * 1024 * 1024
MAX_POST_BYTES = 2 * 1024 * 1024
MAX_CLIENTS = 16


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


def _safe_parts(relative: str) -> list[str] | None:
    clean = relative.strip("/")
    if not clean:
        return []
    parts = clean.split("/")
    if any(not part or part in {".", ".."} or "\x00" in part or "\\" in part for part in parts):
        return None
    return parts


def _media_kind(path: Path) -> str:
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    if path.is_dir():
        return "dir"
    if mime.startswith("image/"):
        return "image"
    if mime.startswith("video/"):
        return "video"
    if mime.startswith("audio/"):
        return "audio"
    if path.suffix.lower() in {".cr2", ".cr3", ".nef", ".arw", ".dng", ".raf", ".orf", ".rw2"}:
        return "raw"
    return "file"


class ShareCatalog:
    """把单目录、多个文件和多个挂载源统一成安全的虚拟目录。"""

    def __init__(self, paths: list[Path]) -> None:
        self.root = paths[0].resolve() if len(paths) == 1 and paths[0].is_dir() else None
        self.mounts = {} if self.root is not None else unique_names(paths)

    def _inside(self, path: Path, base: Path) -> bool:
        try:
            path.resolve().relative_to(base.resolve())
            return True
        except (OSError, ValueError):
            return False

    def resolve(self, relative: str) -> Path | None:
        parts = _safe_parts(relative)
        if parts is None:
            return None
        if self.root is not None:
            candidate = self.root.joinpath(*parts).resolve()
            return candidate if self._inside(candidate, self.root) and candidate.exists() else None
        if not parts:
            return None
        source = self.mounts.get(parts[0])
        if source is None:
            return None
        base = source.resolve()
        if source.is_file():
            return base if len(parts) == 1 and base.exists() else None
        candidate = base.joinpath(*parts[1:]).resolve()
        return candidate if self._inside(candidate, base) and candidate.exists() else None

    def list(self, relative: str) -> list[dict[str, object]] | None:
        parts = _safe_parts(relative)
        if parts is None:
            return None
        clean = "/".join(parts)
        if self.root is None and not parts:
            entries = sorted(self.mounts.items(), key=lambda value: (not value[1].is_dir(), value[0].lower()))
            return [self._item(name, path, name) for name, path in entries if not name.startswith(".")]
        directory = self.resolve(clean)
        if directory is None or not directory.is_dir():
            return None
        output: list[dict[str, object]] = []
        try:
            children = sorted(directory.iterdir(), key=lambda path: (not path.is_dir(), path.name.lower()))
        except OSError:
            return None
        for child in children:
            if child.name.startswith("."):
                continue
            child_relative = f"{clean}/{child.name}" if clean else child.name
            resolved = self.resolve(child_relative)
            if resolved is None:
                continue
            output.append(self._item(child.name, resolved, child_relative))
        return output

    def _item(self, name: str, path: Path, relative: str) -> dict[str, object]:
        stat = path.stat()
        return {
            "path": relative,
            "name": name,
            "size": -1 if path.is_dir() else stat.st_size,
            "modified": int(stat.st_mtime * 1000),
            "mime": "inode/directory" if path.is_dir() else mimetypes.guess_type(name)[0] or "application/octet-stream",
            "kind": _media_kind(path),
        }

    def recursive(self, relative: str, limit: int = MAX_RECURSIVE_FILES) -> tuple[list[dict[str, object]], bool] | None:
        parts = _safe_parts(relative)
        if parts is None:
            return None
        clean = "/".join(parts)
        target = self.resolve(clean)
        if target is not None and target.is_file():
            return [self._item(target.name, target, clean)], False
        if target is None and not (self.root is None and not parts):
            return None
        output: list[dict[str, object]] = []
        pending = [clean]
        while pending:
            current = pending.pop()
            children = self.list(current)
            if children is None:
                return None
            for index, item in enumerate(children):
                if item["kind"] == "dir":
                    pending.append(str(item["path"]))
                else:
                    output.append(item)
                    if len(output) >= limit:
                        return output, bool(pending) or index < len(children) - 1
        return output, False

    def collect(self, files: list[str], directories: list[str]) -> tuple[list[tuple[str, Path]], str | None]:
        selected: dict[str, Path] = {}
        for value in files:
            parts = _safe_parts(value)
            if parts is None or not parts:
                return [], "选择中包含不安全的文件路径"
            relative = "/".join(parts)
            path = self.resolve(relative)
            if path is None or not path.is_file():
                return [], f"文件已经无法读取：{relative}"
            selected[relative] = path
        for value in directories:
            recursive = self.recursive(value, MAX_ZIP_FILES + 1)
            if recursive is None:
                return [], f"目录已经无法读取：{value or '/'}"
            items, truncated = recursive
            if truncated or len(items) > MAX_ZIP_FILES:
                return [], f"目录文件数超过 {MAX_ZIP_FILES:,}，请分批下载"
            for item in items:
                relative = str(item["path"])
                path = self.resolve(relative)
                if path is not None and path.is_file():
                    selected[relative] = path
        if not selected:
            return [], "没有可下载的文件"
        if len(selected) > MAX_ZIP_FILES:
            return [], f"文件数超过 {MAX_ZIP_FILES:,}，请分批下载"
        total = 0
        for path in selected.values():
            try:
                total += path.stat().st_size
            except OSError:
                return [], f"文件已经无法读取：{path.name}"
            if total > MAX_ZIP_BYTES:
                return [], "所选内容超过 8 GiB；大文件请逐个下载以支持断点续传"
        return sorted(selected.items()), None


class PeerDiscovery:
    GROUP = "239.255.42.99"
    BASE_PORT = 42142
    PORT_SPAN = 24
    MAGIC = "nearby-transfer-v1"
    TTL_SECONDS = 8

    def __init__(self, name: str, port: int, path: str) -> None:
        self.name = name[:80]
        self.port = port
        self.path = path
        self.instance = uuid.uuid4().hex
        self.closed = threading.Event()
        self.peers: dict[str, tuple[float, dict[str, object]]] = {}
        self.lock = threading.Lock()
        self.listener: socket.socket | None = None
        self.sender: socket.socket | None = None
        self.listen_port = 0

    def start(self) -> bool:
        try:
            listener = None
            for port in range(self.BASE_PORT, self.BASE_PORT + self.PORT_SPAN):
                candidate = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
                try:
                    candidate.bind(("", port))
                    listener = candidate
                    self.listen_port = port
                    break
                except OSError:
                    candidate.close()
            if listener is None:
                raise OSError("自动发现端口范围已全部占用")
            self.listener = listener
            listener.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.inet_aton(self.GROUP) + socket.inet_aton("0.0.0.0"))
            listener.settimeout(1.0)
            sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            sender.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
            self.sender = sender
        except OSError as exc:
            self.close()
            print(f"警告：局域网自动发现未启动：{exc}", flush=True)
            return False
        threading.Thread(target=self._receive, name="nearby-discovery-receive", daemon=True).start()
        threading.Thread(target=self._announce, name="nearby-discovery-announce", daemon=True).start()
        return True

    def _payload(self) -> bytes:
        return json.dumps(
            {"magic": self.MAGIC, "id": self.instance, "name": self.name, "port": self.port, "path": self.path},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")

    def _announce(self) -> None:
        while not self.closed.is_set():
            value = self._payload()
            sender = self.sender
            if sender is None:
                return
            for port in range(self.BASE_PORT, self.BASE_PORT + self.PORT_SPAN):
                for destination in ((self.GROUP, port), ("255.255.255.255", port)):
                    run_catching(lambda target=destination: sender.sendto(value, target))
            self.closed.wait(2.0)

    def _receive(self) -> None:
        while not self.closed.is_set():
            listener = self.listener
            if listener is None:
                return
            try:
                raw, address = listener.recvfrom(2048)
                data = json.loads(raw.decode("utf-8"))
                peer_id = str(data.get("id", ""))
                if data.get("magic") != self.MAGIC or not peer_id or peer_id == self.instance:
                    continue
                host = address[0]
                if not ipaddress.ip_address(host).is_private:
                    continue
                port = int(data.get("port", 0))
                path = str(data.get("path", ""))
                if port not in range(1, 65536) or not path.startswith("/s/"):
                    continue
                value = {"id": peer_id, "name": str(data.get("name", "附近设备"))[:80], "url": f"http://{host}:{port}{path}"}
                with self.lock:
                    self.peers[peer_id] = (time.monotonic(), value)
            except socket.timeout:
                continue
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                continue

    def snapshot(self) -> list[dict[str, object]]:
        now = time.monotonic()
        with self.lock:
            expired = [key for key, (seen, _) in self.peers.items() if now - seen > self.TTL_SECONDS]
            for key in expired:
                self.peers.pop(key, None)
            return sorted((value for _, value in self.peers.values()), key=lambda item: str(item["name"]).lower())

    def close(self) -> None:
        self.closed.set()
        for value in (self.listener, self.sender):
            if value is not None:
                run_catching(value.close)
        self.listener = None
        self.sender = None


def run_catching(action) -> None:
    try:
        action()
    except Exception:
        pass


BROWSER_HTML = r"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><title>电脑附近快传</title>
<style>
:root{--bg:#09110e;--panel:#111c17;--panel2:#17251e;--ink:#edf7f2;--muted:#91a89d;--line:#294037;--accent:#69ddb0;--accent2:#1f7252;--danger:#ff927f}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 85% -10%,#164331 0,transparent 34rem),var(--bg);color:var(--ink);font-family:Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;min-height:100vh}
button,select{font:inherit}header{position:sticky;top:0;z-index:5;padding:18px clamp(16px,4vw,42px) 14px;background:#09110eea;backdrop-filter:blur(16px);border-bottom:1px solid var(--line)}
.brand{display:flex;align-items:center;justify-content:space-between;gap:14px}.brand h1{font-size:clamp(1.2rem,3vw,1.65rem);margin:0;letter-spacing:-.02em}.badge{font-size:.74rem;color:#a7e8cf;border:1px solid #34775d;border-radius:99px;padding:5px 9px}.sub{color:var(--muted);font-size:.84rem;line-height:1.5}
.toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:13px}.btn{border:1px solid var(--line);border-radius:11px;background:#17261f;color:var(--ink);padding:9px 12px;cursor:pointer}.btn:hover{border-color:#4c806b}.btn:disabled{opacity:.42;cursor:not-allowed}.btn.primary{background:var(--accent2);border-color:#39926e}.btn.ghost{background:transparent}.btn.danger{color:#ffb1a4}.btn.small{padding:6px 9px;font-size:.78rem}
main{max-width:1280px;margin:auto}.peer-wrap{padding:15px clamp(16px,4vw,42px) 0}.peers{display:flex;gap:9px;overflow:auto;padding:4px 0 2px}.peer{display:block;min-width:180px;background:var(--panel);border:1px solid var(--line);border-radius:13px;padding:10px 12px;color:var(--ink);text-decoration:none}.peer strong{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.peer span{color:var(--muted);font-size:.76rem}.crumb{padding:17px clamp(16px,4vw,42px) 0;color:var(--muted);word-break:break-all}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:12px;padding:16px clamp(16px,4vw,42px) 110px}.card{position:relative;background:linear-gradient(155deg,var(--panel2),var(--panel));border:1px solid var(--line);border-radius:16px;overflow:hidden;min-width:0;transition:.16s transform,.16s border-color}.card:hover{transform:translateY(-2px);border-color:#47715f}.card.on{outline:2px solid var(--accent);outline-offset:-2px}.pick{position:absolute;z-index:2;top:10px;left:10px;width:21px;height:21px;accent-color:var(--accent)}
.visual{height:116px;display:flex;align-items:center;justify-content:center;background:linear-gradient(145deg,#0b1611,#15271e);font-size:2.2rem;color:#b6c9c0;cursor:pointer}.meta{padding:11px}.name{font-size:.9rem;font-weight:650;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.size{font-size:.76rem;color:var(--muted);margin-top:4px}.actions{display:flex;gap:6px;padding:0 10px 11px}.actions .btn{flex:1}.empty{grid-column:1/-1;padding:70px 16px;text-align:center;color:var(--muted)}
.selection{position:fixed;z-index:6;left:50%;bottom:18px;transform:translateX(-50%);display:flex;align-items:center;gap:12px;width:min(720px,calc(100% - 28px));padding:11px 12px 11px 16px;border:1px solid #3d6554;border-radius:16px;background:#101d17ed;backdrop-filter:blur(14px);box-shadow:0 12px 45px #0008}.selection .summary{flex:1;min-width:0}.selection strong{display:block}.selection span{font-size:.76rem;color:var(--muted)}
.overlay{display:none;position:fixed;inset:0;z-index:20;background:#000b;align-items:center;justify-content:center;padding:18px}.overlay.show{display:flex}.dialog{width:min(520px,100%);background:#132019;border:1px solid #395c4d;border-radius:18px;padding:20px;box-shadow:0 30px 80px #000b}.dialog h2{margin:0 0 9px}.dialog p{color:var(--muted);line-height:1.6}.dialog .toolbar{justify-content:flex-end}.preview{width:min(920px,100%);max-height:92vh;overflow:auto}.preview-stage{min-height:220px;display:flex;align-items:center;justify-content:center;background:#07100c;border-radius:13px;margin:12px 0;padding:10px}.preview-stage img,.preview-stage video{max-width:100%;max-height:67vh;border-radius:10px}.preview-stage audio{width:min(600px,100%)}
.toast{position:fixed;z-index:30;left:50%;top:18px;transform:translate(-50%,-20px);opacity:0;background:#dff7ec;color:#10231b;border-radius:10px;padding:10px 14px;transition:.2s}.toast.show{opacity:1;transform:translate(-50%,0)}
@media(max-width:600px){header{padding-top:14px}.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.visual{height:103px}.selection{bottom:9px}.selection .btn.ghost{display:none}.badge{display:none}}
</style></head><body>
<header><div class="brand"><div><h1>电脑附近快传</h1><div class="sub" id="device">正在读取分享端…</div></div><span class="badge">局域网直连 · 不经过公网</span></div>
<div class="toolbar"><button class="btn" id="back">← 上一级</button><button class="btn" id="all">全选本页</button><button class="btn ghost" id="none">清空选择</button><button class="btn" id="downloadDir">下载当前目录</button><button class="btn primary" id="downloadSelected">下载所选</button></div></header>
<main><section class="peer-wrap"><div class="sub" id="peerTitle">附近分享端</div><div class="peers" id="peers"></div></section><div class="crumb" id="crumb"></div><div class="grid" id="grid"></div></main>
<div class="selection"><div class="summary"><strong id="selectedTitle">尚未选择</strong><span id="selectedSub">文件和文件夹都可以勾选</span></div><button class="btn ghost" id="clearBottom">清空</button><button class="btn primary" id="downloadBottom">确认下载</button></div>
<div class="overlay" id="confirmOverlay"><div class="dialog"><h2>确认批量下载</h2><p id="confirmText">正在统计…</p><div class="toolbar"><button class="btn ghost" data-close="confirmOverlay">取消</button><button class="btn primary" id="confirmDownload">生成 ZIP 并下载</button></div></div></div>
<div class="overlay" id="previewOverlay"><div class="dialog preview"><h2 id="previewTitle"></h2><div class="preview-stage" id="previewStage"></div><div class="toolbar"><button class="btn ghost" data-close="previewOverlay">关闭</button><a class="btn primary" id="downloadFile" href="#">下载原文件</a></div></div></div>
<iframe name="downloadFrame" hidden></iframe><form id="downloadForm" method="post" target="downloadFrame"></form><div class="toast" id="toast"></div>
<script>
const prefix=location.pathname.replace(/\/$/,'');let dir=new URLSearchParams(location.search).get('dir')||'';let files=[];let info={};let pending=null;const chosen=new Map();const el=id=>document.getElementById(id);const enc=p=>p.split('/').map(encodeURIComponent).join('/');const fileUrl=(p,download=false)=>prefix+'/file/'+enc(p)+(download?'?download=1':'');
function human(n){if(n<0)return'大小未知';let x=n,i=0;const u=['B','KiB','MiB','GiB','TiB'];while(x>=1024&&i<u.length-1){x/=1024;i++}return(i?x.toFixed(2):x)+' '+u[i]}
function icon(kind){return kind==='dir'?'📁':kind==='image'?'▧':kind==='video'?'▶':kind==='audio'?'♪':kind==='raw'?'RAW':'▤'}
function toast(value){const t=el('toast');t.textContent=value;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2300)}
async function api(route){const r=await fetch(prefix+route,{cache:'no-store'});if(!r.ok)throw Error(await r.text());return r.json()}
async function load(){const data=await api('/_api/list?path='+encodeURIComponent(dir));files=data.files||[];el('crumb').textContent=dir?'分享位置 / '+dir.split('/').join(' / '):'分享根目录';el('back').disabled=!dir;render()}
function render(){const g=el('grid');g.innerHTML='';if(!files.length){g.innerHTML='<div class="empty">这个目录是空的</div>';updateSelection();return}for(const f of files){const c=document.createElement('article');c.className='card'+(chosen.has(f.path)?' on':'');const p=document.createElement('input');p.type='checkbox';p.className='pick';p.checked=chosen.has(f.path);p.onclick=e=>{e.stopPropagation();toggle(f,p.checked,c)};const v=document.createElement('div');v.className='visual';v.textContent=icon(f.kind);v.onclick=()=>f.kind==='dir'?go(f.path):preview(f);const m=document.createElement('div');m.className='meta';const n=document.createElement('div');n.className='name';n.textContent=f.name+(f.kind==='dir'?'/':'');const s=document.createElement('div');s.className='size';s.textContent=f.kind==='dir'?'文件夹':human(f.size);m.append(n,s);const a=document.createElement('div');a.className='actions';const open=document.createElement('button');open.className='btn small';open.textContent=f.kind==='dir'?'进入':'预览';open.onclick=()=>f.kind==='dir'?go(f.path):preview(f);const down=document.createElement(f.kind==='dir'?'button':'a');down.className='btn small primary';down.textContent=f.kind==='dir'?'下载目录':'下载';if(f.kind==='dir')down.onclick=()=>prepare([], [f.path]);else{down.href=fileUrl(f.path,true);down.setAttribute('download','')}a.append(open,down);c.append(p,v,m,a);g.appendChild(c)}updateSelection()}
function toggle(f,on,c){if(on)chosen.set(f.path,f);else chosen.delete(f.path);c.classList.toggle('on',on);updateSelection()}
function updateSelection(){const values=[...chosen.values()],dirs=values.filter(v=>v.kind==='dir').length,plain=values.length-dirs;el('selectedTitle').textContent=values.length?`已选 ${plain} 个文件 · ${dirs} 个目录`:'尚未选择';el('selectedSub').textContent=values.length?'下载前会递归统计并再次确认':'文件和文件夹都可以勾选';const enabled=values.length>0;el('downloadSelected').disabled=!enabled;el('downloadBottom').disabled=!enabled}
function clearAll(){chosen.clear();render()}function go(path){dir=path;history.pushState({},'',location.pathname+(dir?'?dir='+encodeURIComponent(dir):''));load().catch(showError)}
async function recursive(path){const value=await api('/_api/list?recursive=1&path='+encodeURIComponent(path));if(value.truncated)throw Error('目录文件过多，请分批下载');return value.files||[]}
async function prepare(directFiles,directories){el('confirmOverlay').classList.add('show');el('confirmText').textContent='正在递归统计文件数量和大小…';el('confirmDownload').disabled=true;try{const unique=new Map();for(const path of directFiles){const item=files.find(v=>v.path===path)||chosen.get(path);if(item&&item.kind!=='dir')unique.set(path,item)}for(const path of directories){for(const item of await recursive(path)){unique.set(item.path,item)}}let bytes=0;for(const item of unique.values())bytes+=Math.max(0,Number(item.size)||0);if(!unique.size)throw Error('没有可下载的文件');if(unique.size>info.maxZipFiles)throw Error(`文件数超过 ${info.maxZipFiles}，请分批下载`);if(bytes>info.maxZipBytes)throw Error('所选内容超过 8 GiB；大文件请逐个下载');pending={files:directFiles,dirs:directories};el('confirmText').textContent=`将 ${unique.size} 个文件（约 ${human(bytes)}）打包成一个 ZIP。服务器边读边发送，不会生成完整临时副本；ZIP 本身不支持断点续传。`;el('confirmDownload').disabled=false}catch(e){pending=null;el('confirmText').textContent='无法准备下载：'+String(e.message||e)}}
function prepareChosen(){const values=[...chosen.values()];prepare(values.filter(v=>v.kind!=='dir').map(v=>v.path),values.filter(v=>v.kind==='dir').map(v=>v.path))}
function submitDownload(){if(!pending)return;const form=el('downloadForm');form.action=prefix+'/_zip';form.innerHTML='';for(const [key,values] of Object.entries({f:pending.files,d:pending.dirs})){for(const value of values){const input=document.createElement('input');input.type='hidden';input.name=key;input.value=value;form.appendChild(input)}}form.submit();el('confirmOverlay').classList.remove('show');toast('已开始生成 ZIP，浏览器将自动下载')}
function preview(f){el('previewTitle').textContent=f.name;const stage=el('previewStage');stage.innerHTML='';if(f.kind==='image'){const image=document.createElement('img');image.src=fileUrl(f.path);stage.appendChild(image)}else if(f.kind==='video'||f.kind==='audio'){const media=document.createElement(f.kind==='audio'?'audio':'video');media.controls=true;media.autoplay=true;media.src=fileUrl(f.path);stage.appendChild(media)}else{stage.textContent='该格式不适合网页预览，请下载原文件'}el('downloadFile').href=fileUrl(f.path,true);el('previewOverlay').classList.add('show')}
function showError(e){el('grid').innerHTML='<div class="empty">读取失败：'+String(e.message||e)+'</div>'}
async function loadPeers(){try{const value=await api('/_api/peers');const peers=value.peers||[],box=el('peers');box.innerHTML='';if(!info.discoverable){el('peerTitle').textContent='自动发现未开启 · 启动时加 --discoverable 可互相看到';return}el('peerTitle').textContent=peers.length?`附近分享端 · ${peers.length} 台在线`:'附近分享端 · 正在等待其他可发现设备';for(const peer of peers){if(!String(peer.url).startsWith('http://'))continue;const a=document.createElement('a');a.className='peer';a.href=peer.url;a.target='_blank';a.rel='noreferrer';const strong=document.createElement('strong');strong.textContent=peer.name;const span=document.createElement('span');span.textContent='点击访问该分享端';a.append(strong,span);box.appendChild(a)}}catch(e){el('peerTitle').textContent='附近分享端发现暂不可用'}}
el('back').onclick=()=>{dir=dir.split('/').slice(0,-1).join('/');history.pushState({},'',location.pathname+(dir?'?dir='+encodeURIComponent(dir):''));load().catch(showError)};el('all').onclick=()=>{files.forEach(f=>chosen.set(f.path,f));render()};el('none').onclick=el('clearBottom').onclick=clearAll;el('downloadSelected').onclick=el('downloadBottom').onclick=prepareChosen;el('downloadDir').onclick=()=>prepare([], [dir]);el('confirmDownload').onclick=submitDownload;document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>el(b.dataset.close).classList.remove('show'));window.onpopstate=()=>{dir=new URLSearchParams(location.search).get('dir')||'';load().catch(showError)};
(async()=>{try{info=await api('/_api/info');el('device').textContent=info.name+(info.discoverable?' · 已允许局域网自动发现':' · 私密链接模式');await Promise.all([load(),loadPeers()]);setInterval(loadPeers,3000)}catch(e){showError(e)}})();
</script></body></html>""".strip()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    token = ""
    catalog: ShareCatalog
    discovery: PeerDiscovery | None = None
    device_name = socket.gethostname()

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

    def do_POST(self) -> None:
        self.close_connection = True
        parsed = urlparse(self.path)
        prefix = f"/s/{self.token}"
        if unquote(parsed.path) != prefix + "/_zip":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            self._text(400, "无效的请求长度", True)
            return
        if length <= 0 or length > MAX_POST_BYTES:
            self._text(413, "下载选择过大，请分批下载", True)
            return
        raw = self.rfile.read(length).decode("utf-8", errors="replace")
        try:
            values = parse_qs(raw, keep_blank_values=True, max_num_fields=MAX_ZIP_FILES * 2 + 100)
        except ValueError:
            self._text(413, "选择项过多，请分批下载", True)
            return
        self._zip(values.get("f", []), values.get("d", []))

    def _authorized(self, path: str) -> tuple[str, str] | None:
        prefix = f"/s/{self.token}"
        if path != prefix and not path.startswith(prefix + "/"):
            return None
        return prefix, path[len(prefix) :]

    def _serve(self, body: bool) -> None:
        self.close_connection = True
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        authorized = self._authorized(path)
        if authorized is None:
            self.send_error(404)
            return
        prefix, route = authorized
        query = parse_qs(parsed.query, keep_blank_values=True)
        if route in {"", "/"}:
            self._html(body)
        elif route == "/_api/info":
            self._json(
                {
                    "name": self.device_name,
                    "discoverable": self.discovery is not None,
                    "maxZipFiles": MAX_ZIP_FILES,
                    "maxZipBytes": MAX_ZIP_BYTES,
                },
                body,
            )
        elif route == "/_api/peers":
            self._json({"peers": [] if self.discovery is None else self.discovery.snapshot()}, body)
        elif route == "/_api/list":
            relative = query.get("path", [""])[0]
            recursive = query.get("recursive", [""])[0].lower() in {"1", "true", "yes"}
            result = self.catalog.recursive(relative) if recursive else None
            if recursive:
                if result is None:
                    self._text(404, "目录不存在", body)
                else:
                    items, truncated = result
                    self._json({"files": items, "truncated": truncated}, body)
            else:
                items = self.catalog.list(relative)
                if items is None:
                    self._text(404, "目录不存在", body)
                else:
                    self._json({"files": items, "truncated": False}, body)
        elif route.startswith("/file/"):
            relative = unquote(route.removeprefix("/file/"))
            target = self.catalog.resolve(relative)
            if target is None or not target.is_file():
                self._text(404, "文件不存在", body)
            else:
                self._file(target, body)
        elif route == "/_zip":
            self._text(405, "请从页面确认批量下载", body)
        else:
            # 兼容旧书签：旧版把目录和文件直接放在令牌路径下。
            relative = route.strip("/")
            target = self.catalog.resolve(relative)
            if target is None:
                self._text(404, "Not found", body)
            elif target.is_dir():
                self.send_response(302)
                self.send_header("Location", f"{prefix}/?dir={quote(relative)}")
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
            else:
                self._file(target, body)

    def _json(self, value: object, body: bool) -> None:
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(raw)

    def _text(self, status: int, value: str, body: bool) -> None:
        raw = value.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(raw)

    def _html(self, body: bool) -> None:
        raw = BROWSER_HTML.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        if body:
            self.wfile.write(raw)

    def _zip(self, files: list[str], directories: list[str]) -> None:
        gate = self.server.zip_gate
        if not gate.acquire(blocking=False):
            self._text(429, "另一个批量下载正在生成，请稍后重试", True)
            return
        try:
            selected, error = self.catalog.collect(files, directories)
            if error is not None:
                self._text(413 if "超过" in error else 400, error, True)
                return
            filename = time.strftime("nearby-transfer-%Y%m%d-%H%M%S.zip")
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Disposition", f"attachment; filename={filename}")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command == "HEAD":
                return
            with zipfile.ZipFile(self.wfile, mode="w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                for relative, path in selected:
                    archive.write(path, arcname=relative)
        finally:
            gate.release()

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
                    suffix = int(right)
                    if suffix <= 0:
                        raise ValueError
                    start = max(total - suffix, 0)
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
        query = parse_qs(urlparse(self.path).query)
        if query.get("download", [""])[0].lower() in {"1", "true", "yes"}:
            fallback = "".join(char if 32 <= ord(char) < 127 and char not in {'"', '\\'} else "_" for char in path.name)
            self.send_header("Content-Disposition", f"attachment; filename=\"{fallback or 'download'}\"; filename*=UTF-8''{quote(path.name, safe='')}")
        else:
            self.send_header("Content-Disposition", "inline")
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
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, server_address, handler_class) -> None:
        self.client_limit = threading.BoundedSemaphore(MAX_CLIENTS)
        self.zip_gate = threading.Semaphore(1)
        super().__init__(server_address, handler_class)

    def process_request(self, request, client_address) -> None:
        self.client_limit.acquire()
        try:
            super().process_request(request, client_address)
        except Exception:
            self.client_limit.release()
            raise

    def process_request_thread(self, request, client_address) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.client_limit.release()

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
    parser.add_argument("--discoverable", action="store_true", help="向同一局域网广播入口，并在网页列出其他分享端")
    parser.add_argument("--name", default=socket.gethostname(), help="自动发现页面显示的设备名称")
    args = parser.parse_args()
    if args.port not in range(0, 65536):
        parser.error("端口必须在 0 到 65535 之间；0 表示自动选择空闲端口")
    paths = [_resolve_input_path(value) for value in args.paths]
    missing = []
    for raw, path in zip(args.paths, paths):
        if not path.exists():
            print(_missing_hint(raw, path), flush=True)
            missing.append(str(path))
    if missing:
        return 2
    Handler.token = secrets.token_urlsafe(24)
    Handler.catalog = ShareCatalog(paths)
    Handler.device_name = str(args.name).strip()[:80] or socket.gethostname()
    Handler.discovery = None
    print(f"共 {len(paths)} 个源", flush=True)
    server = Server(("0.0.0.0", args.port), Handler)
    discovery = None
    if args.discoverable:
        candidate = PeerDiscovery(Handler.device_name, server.server_port, f"/s/{Handler.token}/")
        if candidate.start():
            discovery = candidate
            Handler.discovery = discovery
            print("已开启局域网自动发现：同样使用 --discoverable 的分享端会互相显示。", flush=True)
    else:
        print("当前为私密链接模式；需要多分享端互相发现时加 --discoverable。", flush=True)
    advertise_urls(server.server_port, Handler.token)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        if discovery is not None:
            discovery.close()
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

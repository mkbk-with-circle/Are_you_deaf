#!/usr/bin/env python3
"""你尔多龙吗 · 附近快传电脑下载器（Python 3 标准库，无第三方依赖）。"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from pathlib import Path, PurePosixPath


def human(value: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{int(value)} B" if unit == "B" else f"{value:.2f} {unit}"
        value /= 1024
    return str(value)


def normalize_base(value: str) -> str:
    value = value.strip()
    parsed = urllib.parse.urlparse(value)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError("地址必须是 App 显示的完整 http://.../s/.../ 地址")
    path = parsed.path.rstrip("/")
    if "/s/" not in path:
        raise ValueError("地址缺少临时访问令牌，请从 App 复制完整地址")
    return urllib.parse.urlunparse((parsed.scheme, parsed.netloc, path, "", "", ""))


def api_list(base: str, path: str = "") -> list[dict]:
    query = urllib.parse.urlencode({"path": path})
    url = f"{base}/_api/list?{query}"
    with urllib.request.urlopen(url, timeout=45) as response:
        data = json.loads(response.read().decode("utf-8"))
    return list(data.get("files") or [])


def walk_files(base: str, maximum: int = 200_000) -> list[dict]:
    """逐目录枚举，避免让手机一次递归扫描整张大容量相机卡。"""
    pending: deque[str] = deque([""])
    files: list[dict] = []
    while pending:
        directory = pending.popleft()
        for item in api_list(base, directory):
            if item.get("kind") == "dir":
                pending.append(str(item.get("path") or ""))
            else:
                files.append(item)
                if len(files) >= maximum:
                    raise RuntimeError(f"文件超过安全枚举上限 {maximum}，请在浏览器中进入子目录后分批下载")
    return files


def file_url(base: str, path: str, quality: str = "orig") -> str:
    encoded = "/".join(urllib.parse.quote(part, safe="") for part in PurePosixPath(path).parts)
    query = ""
    if quality != "orig" and path.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".bmp")):
        query = "?" + urllib.parse.urlencode({"q": quality, "download": "1"})
    return f"{base}/file/{encoded}{query}"


def remote_meta(url: str) -> dict:
    request = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(request, timeout=30) as response:
        length = response.headers.get("Content-Length")
        return {
            "url": url,
            "size": int(length) if length else None,
            "etag": response.headers.get("ETag"),
            "last_modified": response.headers.get("Last-Modified"),
        }


def safe_destination(root: Path, remote_path: str, quality: str) -> Path:
    parts = [part for part in PurePosixPath(remote_path).parts if part not in ("", ".", "..")]
    if not parts:
        raise ValueError("远端文件名无效")
    relative = Path(*parts)
    if quality != "orig" and relative.name.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".bmp")):
        relative = relative.with_name(f"{relative.stem}-{quality}.jpg")
    result = root / relative
    result.parent.mkdir(parents=True, exist_ok=True)
    return result


def meta_path(part: Path) -> Path:
    return Path(str(part) + ".meta.json")


def load_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def reset_partial(part: Path) -> None:
    part.unlink(missing_ok=True)
    meta_path(part).unlink(missing_ok=True)


def download_one(url: str, destination: Path, retries: int = 10) -> Path:
    meta = remote_meta(url)
    total = meta.get("size")
    if destination.is_file() and total is not None and destination.stat().st_size == total:
        print(f"已存在，跳过：{destination}")
        return destination

    part = Path(str(destination) + ".part")
    saved = load_json(meta_path(part))
    existing = part.stat().st_size if part.exists() else 0
    if existing and saved:
        changed = (
            saved.get("url") != url
            or (meta.get("etag") and saved.get("etag") and meta["etag"] != saved["etag"])
            or (total is not None and saved.get("size") is not None and total != saved["size"])
            or (
                meta.get("last_modified")
                and saved.get("last_modified")
                and meta["last_modified"] != saved["last_modified"]
            )
        )
        if changed:
            print("远端文件发生变化，旧的断点将被丢弃")
            reset_partial(part)
            existing = 0

    meta_path(part).write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    for attempt in range(retries + 1):
        existing = part.stat().st_size if part.exists() else 0
        headers: dict[str, str] = {}
        mode = "wb"
        if existing:
            headers["Range"] = f"bytes={existing}-"
            if meta.get("etag"):
                headers["If-Range"] = str(meta["etag"])
            elif meta.get("last_modified"):
                headers["If-Range"] = str(meta["last_modified"])
            mode = "ab"
        request = urllib.request.Request(url, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=120)
            if existing and response.status == 200:
                existing = 0
                mode = "wb"
            started = time.monotonic()
            done = existing
            with response, part.open(mode) as output:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
                    done += len(chunk)
                    elapsed = max(time.monotonic() - started, 0.001)
                    speed = (done - existing) / elapsed
                    if total:
                        print(
                            f"\r{done * 100 / total:6.2f}%  {human(done)} / {human(total)}  {human(speed)}/s",
                            end="",
                            flush=True,
                        )
                    else:
                        print(f"\r{human(done)}  {human(speed)}/s", end="", flush=True)
            print()
            if total is not None and done != total:
                raise IOError(f"下载长度不完整：{done} != {total}")
            part.replace(destination)
            meta_path(part).unlink(missing_ok=True)
            print(f"完成：{destination}")
            return destination
        except urllib.error.HTTPError as error:
            if error.code == 416 and total is not None and existing == total:
                part.replace(destination)
                meta_path(part).unlink(missing_ok=True)
                return destination
            if error.code in (401, 403, 404):
                raise RuntimeError("分享地址已失效、文件已移走，或相机卡已经拔出") from error
            failure: Exception = error
        except Exception as error:
            failure = error
        if attempt >= retries:
            raise failure
        wait = min(2**attempt, 30)
        print(f"中断：{failure}；{wait} 秒后从断点重试 ({attempt + 1}/{retries})")
        time.sleep(wait)
    raise RuntimeError("下载失败")


def main() -> int:
    parser = argparse.ArgumentParser(description="从你尔多龙吗附近快传断点续传下载")
    parser.add_argument("url", help="App 显示的完整下载地址（包含 /s/临时令牌/）")
    parser.add_argument("-o", "--output", default=str(Path.home() / "Desktop" / "你尔多龙吗快传"))
    parser.add_argument("--quality", choices=("orig", "high", "mid", "low"), default="orig")
    parser.add_argument("--path", action="append", help="只下载指定相对路径；可重复")
    parser.add_argument("--retries", type=int, default=10)
    args = parser.parse_args()

    base = normalize_base(args.url)
    output = Path(args.output).expanduser().resolve()
    files = walk_files(base)
    if args.path:
        wanted = set(args.path)
        files = [item for item in files if item.get("path") in wanted]
    if not files:
        print("没有可下载文件")
        return 1
    print(f"发现 {len(files)} 个文件，保存到 {output}")
    success = 0
    for item in files:
        path = str(item.get("path") or item.get("name") or "")
        try:
            destination = safe_destination(output, path, args.quality)
            download_one(file_url(base, path, args.quality), destination, args.retries)
            success += 1
        except KeyboardInterrupt:
            print("\n已停止；再次运行同一命令会从 .part 继续")
            return 130
        except Exception as error:
            print(f"失败：{path}：{error}")
    print(f"完成 {success}/{len(files)}")
    return 0 if success == len(files) else 2


if __name__ == "__main__":
    raise SystemExit(main())

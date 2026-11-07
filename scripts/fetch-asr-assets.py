#!/usr/bin/env python3
"""并行分段下载端侧语音识别依赖。

单连接从 GitHub / hf-mirror 拉这些文件实测只有 5–30 KB/s，且经常中途断流；
改成多段 Range 并行后能到 200 KB/s 量级。每段单独落盘，断了重跑只补没下完的段。

用法：
    python scripts/fetch-asr-assets.py [--workers 8]
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = pathlib.Path(__file__).resolve().parent.parent
AAR = ROOT / "app" / "libs" / "sherpa-onnx-2.0.1.aar"
MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "asr"

# GitHub Releases 直连不可靠，也不存在国内镜像（Gitee 只有代码镜像、没有 Release 附件），
# 只能走支持 Range 的代理。实测各代理时好时坏，故每个分段逐条尝试，谁通用谁。
GH_PROXIES = [
    "",  # 优先使用官方直连；不可用时才尝试代理
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
    "https://ghfast.top/",
]
HF_MIRROR = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13/resolve/main/"
GH_AAR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"

# (下载地址, 落地路径, 字节数, SHA-256)；地址可给多个候选，逐个尝试
TASKS = [
    (
        [proxy + GH_AAR for proxy in GH_PROXIES],
        AAR,
        49_441_341,
        "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7",
    ),
    (
        [HF_MIRROR + "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx"],
        MODEL_DIR / "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
        70_104_213,
        "d6380c74c75aaf37a739061a9197440fc5ca3f73ee339e588268eff2e9d3bf84",
    ),
    (
        [HF_MIRROR + "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx"],
        MODEL_DIR / "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
        1_033_417,
        "7fa442b8b35b1ab217dbceadb57a7da5388ee445c6c722eab576a57071e0dcea",
    ),
    (
        [HF_MIRROR + "decoder-epoch-20-avg-1-chunk-16-left-128.onnx"],
        MODEL_DIR / "decoder-epoch-20-avg-1-chunk-16-left-128.onnx",
        5_165_084,
        "93f0df50c2834fe225bbabd664d59fc2488b15736fbc6acaabddec3188dea9b4",
    ),
    (
        [HF_MIRROR + "tokens.txt"],
        MODEL_DIR / "tokens.txt",
        18_626,
        "6722bd1585f46f84456b29c3550a343a3cc375b971645773c02ed8e0b4e2405c",
    ),
]

CHUNK = 4 * 1024 * 1024


def fetch_chunk(url: str, part: pathlib.Path, start: int, end: int) -> None:
    """功能：下载一个字节区间到分段文件，已下部分不重复拉取。

    参数：url 下载地址；part 分段文件路径；start 起始字节；end 结束字节（含）
    返回：无；失败时抛异常
    """
    already = part.stat().st_size if part.exists() else 0
    offset = start + already
    if offset > end:
        return
    request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-{end}"})
    with urllib.request.urlopen(request, timeout=120) as response:
        # 服务端若忽略 Range 返回 200，会从头给数据；这种情况下必须覆盖而不是追加
        append = response.status == 206 and already > 0
        mode = "ab" if append else "wb"
        with part.open(mode) as handle:
            shutil.copyfileobj(response, handle, length=256 * 1024)


def sha256(path: pathlib.Path) -> str:
    """计算文件 SHA-256，使用分块读取避免把模型一次载入内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(urls: list[str], dest: pathlib.Path, size: int, expected_sha256: str, workers: int) -> None:
    """功能：并行分段下载一个文件并校验体积与 SHA-256。

    参数：urls 候选下载地址（按顺序轮换，谁通用谁）；dest 目标路径；
          size 期望字节数；expected_sha256 期望摘要；workers 并发段数
    返回：无；失败时抛异常
    """
    if dest.exists():
        if dest.stat().st_size == size and sha256(dest) == expected_sha256:
            print(f"已存在且校验通过：{dest.name}")
            return
        raise RuntimeError(f"{dest.name} 已存在但校验失败，请移走该文件后重试")

    dest.parent.mkdir(parents=True, exist_ok=True)
    part_dir = dest.with_suffix(dest.suffix + ".parts")
    part_dir.mkdir(exist_ok=True)

    ranges = [(i, start, min(start + CHUNK - 1, size - 1))
              for i, start in enumerate(range(0, size, CHUNK))]
    print(f"下载：{dest.name}（{len(ranges)} 段 / {workers} 并发 / {size} 字节）")

    def run(item: tuple[int, int, int]) -> tuple[int, bool]:
        index, start, end = item
        part = part_dir / f"{index:04d}"
        expected = end - start + 1
        for attempt in range(max(4, len(urls) * 2)):
            url = urls[attempt % len(urls)]
            try:
                fetch_chunk(url, part, start, end)
                if part.exists() and part.stat().st_size == expected:
                    return index, True
            except Exception as error:  # 网络抖动与 5xx 都按同一策略重试
                print(f"  段 {index} 第 {attempt + 1} 次失败（{urllib.parse.urlsplit(url).netloc}）：{error}",
                      file=sys.stderr)
            time.sleep(1)
        return index, False

    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(run, ranges))

    failed = [index for index, ok in results if not ok]
    if failed:
        raise RuntimeError(f"{dest.name} 有 {len(failed)} 段未完成，重跑脚本会接着下")

    tmp = dest.with_suffix(dest.suffix + ".tmp")
    with tmp.open("wb") as out:
        for index, _, _ in ranges:
            with (part_dir / f"{index:04d}").open("rb") as piece:
                shutil.copyfileobj(piece, out)
    if tmp.stat().st_size != size:
        raise RuntimeError(f"{dest.name} 体积不符：{tmp.stat().st_size} / {size}")
    if sha256(tmp) != expected_sha256:
        raise RuntimeError(f"{dest.name} SHA-256 不符，请删除 {part_dir} 后重试")
    tmp.replace(dest)
    shutil.rmtree(part_dir, ignore_errors=True)
    print(f"完成：{dest.name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    for urls, dest, size, expected_sha256 in TASKS:
        download(urls, dest, size, expected_sha256, args.workers)
    return 0


if __name__ == "__main__":
    sys.exit(main())

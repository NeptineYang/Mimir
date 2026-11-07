#!/usr/bin/env python3
"""拉取 Mimir 的运行状态（真机/模拟器联调用）。

只读：从设备上把 Room 数据库（含 -wal / -shm）拷到临时目录，再用 sqlite3 读出探针、
任务、最新任务的审计、授权与写入意图。适合在真机联调时回答"到底卡在哪一步"——
不要靠截图 OCR 猜状态。

用法：
    python scripts/pull-state.py
    python scripts/pull-state.py --out build/state    # 保留数据库副本

adb 查找顺序：环境变量 ADB → PATH → local.properties 的 sdk.dir → ANDROID_HOME/ANDROID_SDK_ROOT。
"""

import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

PKG = "com.neptine.mimir"
DB_FILES = ("mimir.db", "mimir.db-wal", "mimir.db-shm")


def find_adb() -> str:
    """按优先级查找 adb 可执行文件；找不到时报错退出。"""
    candidate = os.environ.get("ADB")
    if candidate and Path(candidate).exists():
        return candidate
    candidate = shutil.which("adb")
    if candidate:
        return candidate

    props = Path(__file__).resolve().parent.parent / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip().replace("/", os.sep)
                exe = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
                if exe.exists():
                    return str(exe)

    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(key)
        if sdk:
            exe = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if exe.exists():
                return str(exe)

    sys.exit("找不到 adb：设置环境变量 ADB，或把 platform-tools 加进 PATH")


def pull_db(adb: str, out_dir: Path) -> None:
    """把数据库的三个文件拉到本地目录；-wal/-shm 不在时跳过。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    for name in DB_FILES:
        result = subprocess.run(
            [adb, "exec-out", "run-as", PKG, "cat", f"databases/{name}"],
            capture_output=True,
            text=False,
        )
        if result.returncode != 0 or not result.stdout:
            if name == "mimir.db":
                detail = result.stderr.decode(errors="ignore").strip()
                sys.exit(f"拉取 mimir.db 失败：{detail or '空输出'}")
            continue
        (out_dir / name).write_bytes(result.stdout)


def fmt_time(millis: int) -> str:
    """毫秒时间戳格式化成本地时间。"""
    return time.strftime("%m-%d %H:%M:%S", time.localtime(millis / 1000))


def print_table(conn: sqlite3.Connection, title: str, sql: str, params=()) -> None:
    """执行查询并按列打印；表不存在时明确写出来。"""
    print(f"\n{title}")
    try:
        rows = list(conn.execute(sql, params))
    except sqlite3.Error as error:
        print(f"  （跳过：{error}）")
        return
    if not rows:
        print("  （空）")
        return
    for row in rows:
        print("  " + " | ".join("" if value is None else str(value) for value in row))


def print_usage(conn: sqlite3.Connection, task_id: str) -> None:
    """打印该任务的模型 token 用量（4→5 迁移后才有实测值；旧行为 0）。"""
    try:
        row = conn.execute(
            "select count(*), sum(prompt_tokens), sum(completion_tokens) from audit "
            "where task_id = ? and (prompt_tokens > 0 or completion_tokens > 0)",
            (task_id,),
        ).fetchone()
    except sqlite3.Error:
        print("模型用量：该库还没有 prompt_tokens 列（安装新版本后再看）")
        return
    calls, tin, tout = row[0] or 0, row[1] or 0, row[2] or 0
    if calls == 0:
        print("模型用量：（无实测记录）")
        return
    avg_in = tin // calls
    avg_out = tout // calls
    print(f"模型用量：{calls} 次调用，输入 {tin:,} / 输出 {tout:,} / 合计 {tin + tout:,} token")
    print(f"          单次均值 输入 {avg_in:,} / 输出 {avg_out:,} token")


def main() -> None:
    parser = argparse.ArgumentParser(description="拉取 Mimir 状态")
    parser.add_argument("--out", help="数据库副本目录；默认用系统临时目录")
    args = parser.parse_args()

    adb = find_adb()
    out_dir = Path(args.out) if args.out else Path(tempfile.mkdtemp(prefix="mimir-state-"))
    pull_db(adb, out_dir)

    conn = sqlite3.connect(str(out_dir / "mimir.db"))
    print(f"数据库副本：{out_dir}")

    print_table(
        conn,
        "探针（device_capability）",
        "select probe_id, passed, detail from device_capability order by probe_id",
    )

    print("\n任务（最新 10 条）")
    try:
        tasks = list(
            conn.execute(
                "select task_id, created_at, state, step_count, budget_model_calls, "
                "suspend_reason, package_name from tasks order by created_at desc limit 10"
            )
        )
    except sqlite3.Error as error:
        sys.exit(f"读取 tasks 失败：{error}")
    if not tasks:
        print("  （空）")
        return
    for row in tasks:
        print(
            "  " + fmt_time(row[1]),
            row[0][:8],
            row[2],
            f"steps={row[3]}",
            f"models={row[4]}",
            f"pkg={row[6] or '-'}",
            f"| {row[5] or ''}",
        )

    newest = tasks[0][0]
    print_usage(conn, newest)
    print_table(
        conn,
        f"最新任务 {newest[:8]} 的审计",
        "select step_idx, action_kind, outcome, risk_level, decision, target_text, result "
        "from audit where task_id = ? order by step_idx, id",
        (newest,),
    )
    print_table(
        conn,
        f"最新任务 {newest[:8]} 的写入意图",
        "select action_kind, status, outcome, system_return_value, evidence "
        "from action_intent where task_id = ? order by step_idx",
        (newest,),
    )
    print_table(
        conn,
        f"最新任务 {newest[:8]} 的授权",
        "select action_kind, consumed, params_digest from risk_grant where task_id = ?",
        (newest,),
    )


if __name__ == "__main__":
    main()

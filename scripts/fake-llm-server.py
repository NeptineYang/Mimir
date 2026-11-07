#!/usr/bin/env python3
"""本地假模型后端：验证 Mimir 的 Agent 主循环（不接真实模型）。

按 system prompt 分流：
- 任务解析器 -> TaskSpec JSON（同时清零计数，方便反复跑同一任务）
- 任务规划器 -> TaskPlan JSON（受限重规划走同一分支，响应更短）
- 其它（Executor）-> 默认剧本：第一次 tap 元素表里的「关于手机」（找不到就点第一个有文本的
  可点击元素），第二次 finish；也可用环境变量构造指定场景：
    FAKE_PACKAGE     目标包名，默认 com.android.settings
    FAKE_TAP_LABELS  逗号分隔的点击序列，第 N 个标签在第 N 次调用时点；
                     暂时找不到就先返回上一页（最多 3 次），找完就 finish
    FAKE_REQUIRED_FIELDS  逗号分隔的完成必填字段，用于构造初验不通过（MISMATCH）
    FAKE_STEP_DONE   置 1 时走计划推进剧本：tap → step_done（后置条件取「Android 版本」）
    FAKE_REPLAN      置 1 时先发一次 replan，再按计划推进剧本走

  PowerShell 示例：
    $env:FAKE_TAP_LABELS='网络和互联网,互联网,AndroidWifi,移除'
    python scripts/fake-llm-server.py
"""

import json
import os
import time
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8799

# 调试导出：把 Executor 收到的用户消息写到系统临时目录，方便核对元素表（不属于交付物）。
PROMPT_DUMP = os.path.join(os.environ.get("TEMP", "."), "mimir_last_prompt.txt")

# 可按环境变量指定目标包名与点击序列，用来构造特定场景（例如命中风险词的 L2A）。
# FAKE_TAP_LABELS 用逗号分隔：第 N 次 Executor 调用点含第 N 个标签的元素，标签缺失就 finish。
FAKE_PACKAGE = os.environ.get("FAKE_PACKAGE", "com.android.settings")
FAKE_TAP_LABELS = [item for item in os.environ.get("FAKE_TAP_LABELS", "").split(",") if item]
FAKE_REQUIRED_FIELDS = [
    item for item in os.environ.get("FAKE_REQUIRED_FIELDS", "").split(",") if item
]

# 文本输入场景：设了就按「点搜索框 → 往输入框写字 → 检查字是否出现在元素表」走三步，
# 用来验证无障碍 ACTION_SET_TEXT 路径（不切输入法）是否真的把文字写进了目标输入框。
FAKE_INPUT_TEXT = os.environ.get("FAKE_INPUT_TEXT", "")

# 计划推进剧本：tap → step_done。用来验证 §1.3.2 的"声明 + 本地复核"与计划走完直接进验证。
FAKE_STEP_DONE = os.environ.get("FAKE_STEP_DONE", "") not in ("", "0")

# 计划推进剧本要点开的入口文案。模拟器上设置里叫「关于模拟设备」，真机是「关于手机」，
# 两个都试，找到哪个点哪个。
FAKE_TARGET_LABELS = [
    item for item in os.environ.get(
        "FAKE_TARGET_LABEL", "关于模拟设备,关于手机",
    ).split(",") if item
]

# 受限重规划剧本：第一次返回 replan，随后按计划推进剧本继续。
FAKE_REPLAN = os.environ.get("FAKE_REPLAN", "") not in ("", "0")

# 凭据剧本：进入 Mimir 设置 → 登录密码 → 新增 → 在密码框用第一条可用凭据填充。
# 需要先在设备上用凭据页保存一条绑定 com.neptine.mimir 的登录密码。
FAKE_CREDENTIAL = os.environ.get("FAKE_CREDENTIAL", "") not in ("", "0")

# 只发一次 credential 就 finish：用于"任务已停在 NEED_CONFIRM，点继续后只差这一步"的场景。
FAKE_CREDENTIAL_ONCE = os.environ.get("FAKE_CREDENTIAL_ONCE", "") not in ("", "0")

# 每次响应前的人为延迟（毫秒）。用来构造"模型调用进行中把应用杀掉"的中断场景，
# 否则本地假后端毫秒级返回，几乎没有窗口在 EXECUTING 态杀进程。
FAKE_DELAY_MS = int(os.environ.get("FAKE_DELAY_MS", "0"))

# 第一次只 wait、不点任何东西：用来验证"注入到 prompt 的记忆片段"——
# 页面不变，最后一次 Executor 请求的 prompt dump 里就能看到 [已知操作经验] 段。
FAKE_WAIT_FIRST = os.environ.get("FAKE_WAIT_FIRST", "") not in ("", "0")

# 全场调用计数与标签进度；每次解析新任务时归零。
CALLS = {"executor": 0, "position": 0, "backs": 0, "credential_actions": 0, "cred_stage": 0}

# 目标标签暂时不在当前页面时，最多先返回几页去找（模拟"局部自愈"）。
MAX_BACKS = 3


def text_of(content):
    """把 content（字符串或内容块数组）统一成纯文本。"""
    if isinstance(content, str):
        return content
    parts = []
    for block in content or []:
        if isinstance(block, dict) and block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "\n".join(parts)


def element_for(user_text, label):
    """在元素表里找含 label 且可点击的第一个元素编号。"""
    for line in user_text.splitlines():
        if label in line and line.strip().startswith("#") and "可点击" in line:
            match = re.match(r"#(\d+)", line.strip())
            if match:
                return int(match.group(1))
    return None


def first_clickable(user_text):
    """找元素表里第一个有文本的可点击元素。

    跳过「(无文本)」的行：图标容器这类元素没有语义，Guard 会按 fail-closed 拦成
    L2A 并转人工，拿它当第一跳会把假后端的测试卡在 NEED_HUMAN。
    """
    for line in user_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") and "可点击" in stripped and "(无文本)" not in stripped:
            match = re.match(r"#(\d+)", stripped)
            if match:
                return int(match.group(1))
    return None


def first_editable(user_text):
    """找元素表里第一个可输入元素。"""
    for line in user_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") and "可输入" in stripped:
            match = re.match(r"#(\d+)", stripped)
            if match:
                return int(match.group(1))
    return None


def tap_index(user_text):
    """按配置决定本轮点哪个元素。

    返回 ("tap", 编号) / ("back", None) / (None, None)。配了 FAKE_TAP_LABELS 就按序列走：
    标签不在当前页面时先返回上一页去找（最多 MAX_BACKS 次），找够了就 finish。
    没配则走默认剧本：第一次点「关于手机」或第一个有文本的可点击元素，之后 finish。
    """
    if FAKE_TAP_LABELS:
        if CALLS["position"] >= len(FAKE_TAP_LABELS):
            return None, None
        index = element_for(user_text, FAKE_TAP_LABELS[CALLS["position"]])
        if index is not None:
            CALLS["position"] += 1
            return "tap", index
        if CALLS["backs"] < MAX_BACKS:
            CALLS["backs"] += 1
            return "back", None
        return None, None

    if CALLS["executor"] != 1:
        return None, None
    return "tap", (element_for(user_text, "关于手机") or first_clickable(user_text))


def choose_action(user_text):
    """返回 (元素编号, 动作类型)；动作类型为 tap / back / None（finish）。"""
    action, index = tap_index(user_text)
    return index, action


def input_scenario(user_text):
    """文本输入三步剧本：点搜索框 → 写文本 → 用元素表核对是否写进去了。"""
    step = CALLS["executor"]
    if step == 1:
        index = element_for(user_text, "搜索") or first_clickable(user_text)
        if index is None and CALLS["backs"] < MAX_BACKS:
            CALLS["backs"] += 1
            return {"action": "back"}
        if index is None:
            return {"action": "finish", "summary": "没找到可点的搜索入口"}
        return {"action": "tap", "element": index}
    if step == 2:
        index = first_editable(user_text) or element_for(user_text, "搜索")
        if index is None:
            return {"action": "finish", "summary": "没找到可输入的元素"}
        return {"action": "input", "element": index, "text": FAKE_INPUT_TEXT}
    found = FAKE_INPUT_TEXT in user_text
    return {"action": "finish", "summary": "FOUND" if found else "NOT_FOUND"}


def password_element(user_text):
    """在元素表里找带「密码框」且可输入的元素编号。"""
    for line in user_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") and "密码框" in stripped and "可输入" in stripped:
            match = re.match(r"#(\d+)", stripped)
            if match:
                return int(match.group(1))
    return None


def credential_id(user_text):
    """从 [可用凭据] 段里取第一条凭据编号。"""
    for line in user_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("- cred-"):
            return stripped[2:].split("｜", 1)[0].strip()
    return None


def credential_scenario(user_text):
    """凭据剧本：导航到新增凭据对话框，然后用保存的凭据填充密码框。

    第一次 credential 会被 Guard 拦成 L2A NEED_CONFIRM；用户点「继续一次」后
    同一条动作再次出现，拿到一次性授权后由 CredentialBroker 本地填充。
    第二次 credential 之后再调用就 finish。

    每一步都按"当前页面看得见什么"重新选动作，而不是记一个阶段号：
    页面切换可能失败或延迟，阶段号一旦错位就会一直发 wait。
    """
    target_actions = 1 if FAKE_CREDENTIAL_ONCE else 2
    if CALLS["credential_actions"] >= target_actions:
        return {"action": "finish", "summary": "已用凭据填充密码框"}
    password_index = password_element(user_text)
    credential = credential_id(user_text)
    if password_index is not None and credential is not None:
        CALLS["credential_actions"] += 1
        return {"action": "credential", "element": password_index, "credential": credential}
    for label in ("新增", "登录凭据"):
        index = element_for(user_text, label)
        if index is not None:
            return {"action": "tap", "element": index}
    # 已经在设置页（能看到设置页的分区标题）时不要反复点 SETUP，往下滚找入口
    if "记忆与凭据" in user_text or "配方与策略" in user_text or "模型后端" in user_text:
        if CALLS["backs"] < 6:
            CALLS["backs"] += 1
            return {"action": "swipe", "direction": "up", "distance": 45}
        return {"action": "finish", "summary": "设置页找不到凭据入口"}
    index = element_for(user_text, "SETUP") or element_for(user_text, "设置")
    if index is not None:
        return {"action": "tap", "element": index}
    return {"action": "wait", "seconds": 1}


def plan_scenario(user_text):
    """计划推进剧本：先 replan（可选），再滚到目标并 tap，最后声明 step_done。

    后置条件「Android 版本」只在关于手机页面上出现，因此 step_done 能不能推进游标
    完全取决于这一步点击是否真的生效——这正是要验证的本地复核。
    「关于手机」在设置首页最底部，首屏元素表里没有，所以先用 swipe 往下找。
    """
    if FAKE_REPLAN and CALLS["executor"] == 1:
        return {"action": "replan", "reason": "当前页面看不到「关于手机」入口"}
    if CALLS["position"] == 0:
        index = None
        for label in FAKE_TARGET_LABELS:
            index = element_for(user_text, label)
            if index is not None:
                break
        if index is None:
            if CALLS["backs"] < MAX_BACKS:
                CALLS["backs"] += 1
                return {"action": "swipe", "direction": "up", "distance": 60}
            return {"action": "finish", "summary": "没找到目标入口"}
        CALLS["position"] = 1
        return {"action": "tap", "element": index}
    return {"action": "step_done", "summary": "已进入关于手机页面"}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if FAKE_DELAY_MS > 0:
            time.sleep(FAKE_DELAY_MS / 1000.0)
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages") or []
        system = text_of(messages[0].get("content")) if messages else ""
        user = text_of(messages[-1].get("content")) if messages else ""

        if "任务解析器" in system:
            CALLS["executor"] = 0
            CALLS["position"] = 0
            CALLS["backs"] = 0
            CALLS["credential_actions"] = 0
            CALLS["cred_stage"] = 0
            reply = {
                "taskType": "NAVIGATE_SETTINGS",
                "package": FAKE_PACKAGE,
                "parameters": {},
                "constraints": {"maxAmountCents": None, "forbiddenActions": [], "notes": []},
                "completion": {
                    "successBoundary": "设置应用里完成一次点击",
                    "requiredFields": FAKE_REQUIRED_FIELDS,
                },
            }
        elif "手机任务规划器" in system:
            reply = {
                "assumptions": ["目标应用可以正常启动"],
                "subtasks": [
                    {
                        "objective": "在设置里打开关于手机页面",
                        "package": FAKE_PACKAGE,
                        "steps": [
                            {
                                "intent": "打开设备的关于页",
                                "parameters": [],
                                "precondition": "设置首页",
                                "postcondition": ["Android 版本"],
                            }
                        ],
                    }
                ],
            }
        else:
            CALLS["executor"] += 1
            # 调试用：把本轮用户消息落到临时文件，方便核对元素表（不属于交付物）
            try:
                with open(PROMPT_DUMP, "w", encoding="utf-8") as dump:
                    dump.write(user)
            except OSError:
                pass

            if FAKE_CREDENTIAL:
                reply = credential_scenario(user)
            elif FAKE_INPUT_TEXT:
                reply = input_scenario(user)
            elif FAKE_WAIT_FIRST and CALLS["executor"] == 1:
                reply = {"action": "wait", "seconds": 1}
            elif FAKE_STEP_DONE or FAKE_REPLAN:
                reply = plan_scenario(user)
            else:
                index, action = choose_action(user)
                if action == "tap":
                    reply = {"action": "tap", "element": index}
                elif action == "back":
                    reply = {"action": "back"}
                else:
                    reply = {"action": "finish", "summary": "已点击目标元素"}

        payload = {
            "id": "fake-1",
            "choices": [
                {"index": 0, "message": {"role": "assistant", "content": json.dumps(reply, ensure_ascii=False)},
                 "finish_reason": "stop"}
            ],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
        }
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("[fake-llm]", fmt % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

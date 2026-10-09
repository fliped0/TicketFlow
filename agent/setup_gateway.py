"""Create private local gateway configuration; never send the key anywhere."""

import argparse
import getpass
import json
import os
import subprocess
import uuid
from pathlib import Path

from ticketflow_agent.config import Settings

ROOT = Path(__file__).resolve().parent.parent


def save_local(values, *, replace=False):
    target = ROOT / "config/local/agent.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and not replace:
        raise ValueError("Local configuration exists; use -Replace only to rotate/update it")
    Settings(model_mode="gateway", **values)
    temp = target.with_name("agent-" + uuid.uuid4().hex + ".tmp")
    try:
        temp.touch(exist_ok=False)
        if os.name == "nt":
            account = subprocess.check_output(["whoami"], text=True).strip()
            subprocess.run(
                ["icacls", str(temp), "/inheritance:r", "/grant:r", account + ":(F)", "SYSTEM:(F)"],
                check=True,
                capture_output=True,
            )
        else:
            temp.chmod(0o600)
        with temp.open("w", encoding="utf-8") as file:
            json.dump(values, file, ensure_ascii=False, indent=2)
            file.write("\n")
        # Replacement is explicitly requested; never touches the usage ledger.
        if not replace and target.exists():
            raise ValueError("Configuration was created by another process")
        os.replace(temp, target)
    finally:
        temp.unlink(missing_ok=True)
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-http", action="store_true")
    parser.add_argument("--share-order-data", action="store_true")
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    print("学校网关：http://aigw.dlut.edu.cn/v1；模型：DeepSeek-V4-Flash-0731-W8A8")
    if not args.allow_http:
        parser.error("School gateway currently uses HTTP; add --allow-http on a trusted network")
    print("请输入重新生成的密钥，不回显、不联网、不写入版本库。")
    values = {
        "gateway_url": "http://aigw.dlut.edu.cn/v1",
        "gateway_model": "DeepSeek-V4-Flash-0731-W8A8",
        "gateway_key": getpass.getpass("新 API Key: ").strip(),
        "allow_http_gateway": args.allow_http,
        "allow_private_model_data": args.share_order_data,
        "daily_requests": 20,
        "user_daily_requests": 10,
        "requests_per_minute": 10,
        "daily_token_budget": 200000,
    }
    try:
        target = save_local(values, replace=args.replace)
    except (ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit(
            "本地配置失败：检查密钥格式、文件权限或是否需要 -Replace；未打印密钥"
        ) from None
    print(f"本地配置已保存：{target}")
    print("每日最多20次模型请求、每用户10次、每分钟10次；未知用量保留预留额度。")
    print("订单结果外发：" + ("已开启" if args.share_order_data else "关闭；直接查询工具仍可使用"))


if __name__ == "__main__":
    main()

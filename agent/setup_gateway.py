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


def save_local(values, *, replace=False, pending_key=False):
    target = ROOT / "config/local/agent.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and not replace:
        raise ValueError("Local configuration exists; use -Replace only to rotate/update it")
    Settings(model_mode="disabled" if pending_key else "gateway", **values)
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


def prepare_switch(url, model):
    target = ROOT / "config/local/agent.json"
    values = json.loads(target.read_text("utf-8")) if target.exists() else {}
    if values.get("gateway_key") and not values.get("gateway_key_url"):
        values["gateway_key_url"] = values.get("gateway_url", "http://aigw.dlut.edu.cn/v1")
    values.update(gateway_url=url, gateway_model=model, allow_http_gateway=False)
    # Bind even empty credentials so the target is validated before saving.
    values.setdefault("gateway_key_url", url)
    return save_local(values, replace=True, pending_key=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=Settings.gateway_url)
    parser.add_argument("--model", default=Settings.gateway_model)
    parser.add_argument("--prepare-switch", action="store_true")
    parser.add_argument("--allow-http", action="store_true")
    parser.add_argument("--share-order-data", action="store_true")
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()
    print(f"模型接口：{args.url}；模型：{args.model}")
    if args.prepare_switch:
        try:
            prepare_switch(args.url, args.model)
        except (ValueError, OSError, subprocess.SubprocessError):
            raise SystemExit("接口配置失败；未打印密钥") from None
        print("接口和模型已更新；原密钥仍绑定原接口，请重新运行 --replace 输入新接口密钥。")
        return
    if args.url.startswith("http:") and not args.allow_http:
        parser.error("School gateway currently uses HTTP; add --allow-http on a trusted network")
    print("请输入当前接口的 API 密钥，不回显、不联网、不写入版本库。")
    target = ROOT / "config/local/agent.json"
    previous = json.loads(target.read_text("utf-8")) if target.exists() else {}
    values = {
        "gateway_url": args.url,
        "gateway_model": args.model,
        "gateway_key": getpass.getpass("新 API Key: ").strip(),
        "gateway_key_url": args.url,
        "allow_http_gateway": args.allow_http,
        "allow_private_model_data": args.share_order_data,
        "daily_requests": previous.get("daily_requests", Settings.daily_requests),
        "user_daily_requests": previous.get("user_daily_requests", Settings.user_daily_requests),
        "requests_per_minute": previous.get("requests_per_minute", Settings.requests_per_minute),
        "daily_token_budget": previous.get("daily_token_budget", Settings.daily_token_budget),
    }
    try:
        target = save_local(values, replace=args.replace)
    except (ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit(
            "本地配置失败：检查密钥格式、文件权限或是否需要 -Replace；未打印密钥"
        ) from None
    print(f"本地配置已保存：{target}")
    print("本地请求/用量限制与历史账本保留；实际计费和平台额度以新接口控制台为准。")
    print("模型本人订单查询：" + ("已开启；结果仅在本地显示" if args.share_order_data else "关闭"))


if __name__ == "__main__":
    main()

"""Interactive local query client; token stays in memory and is entered without echo."""

import argparse
import getpass
import json
import re

import httpx


def show_confirmations(client, body):
    data = body.get("data", {})
    cards = data.get("confirmations", []) if isinstance(data, dict) else []
    for card in cards:
        if card["state"] != "PENDING":
            continue
        print(
            f"\n待确认：{card['impact']}；订单 {card['orderId']}；"
            f"金额 {card['amountFen']} 分；确认到期 {card['expiresAt']}"
        )
        answer = input("独立确认：输入 确认执行 执行此操作；空行保留待确认：").strip()
        if answer == "确认执行":
            result = client.post(
                f"/agent/v1/confirmations/{card['confirmationId']}/execute",
                json={"approved": True},
            )
            print(json.dumps(result.json(), ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chat", action="store_true")
    args = parser.parse_args()
    print("TicketFlow 聊天与确认客户端" if args.chat else "TicketFlow 直接查询工具客户端")
    if args.chat:
        print("/结果 确认ID：查执行状态；/恢复 确认ID：按原请求检查未知结果。")
    token = getpass.getpass("粘贴现有 Java 登录令牌（不回显）: ")
    with httpx.Client(
        base_url="http://127.0.0.1:8090",
        trust_env=False,
        timeout=50,
        follow_redirects=False,
        headers={"Authorization": f"Bearer {token}"},
    ) as client:
        response = client.get("/agent/v1/tools")
        if response.status_code != 200:
            print(response.text)
            return
        for tool in response.json()["data"] if not args.chat else []:
            print(f"{tool['name']}: {tool['description']}")
        session_id = None
        while True:
            if args.chat:
                message = input("\n问题（空行退出）: ").strip()
                if not message:
                    break
                command = re.fullmatch(r"/(结果|恢复) ([0-9a-f-]{36})", message)
                if command:
                    path = f"/agent/v1/confirmations/{command[2]}"
                    result = (
                        client.get(path)
                        if command[1] == "结果"
                        else client.post(path + "/recover", json={})
                    )
                    print(json.dumps(result.json(), ensure_ascii=False, indent=2))
                    continue
                result = client.post(
                    "/agent/v1/chat", json={"message": message, "sessionId": session_id}
                )
                body = result.json()
                if result.status_code == 200:
                    session_id = body["data"]["sessionId"]
                print(json.dumps(body, ensure_ascii=False, indent=2))
                if result.status_code == 200:
                    show_confirmations(client, body)
                continue
            tool = input("\n工具名（空行退出）: ").strip()
            if not tool:
                break
            try:
                arguments = json.loads(
                    input('参数 JSON（例如 {"city":"杭州"}，空行表示 {}）: ') or "{}"
                )
            except json.JSONDecodeError:
                print("JSON 格式无效")
                continue
            result = client.post("/agent/v1/query", json={"tool": tool, "arguments": arguments})
            print(json.dumps(result.json(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

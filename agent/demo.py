"""Interactive local query client; token stays in memory and is entered without echo."""

import argparse
import getpass
import json

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chat", action="store_true")
    args = parser.parse_args()
    print("TicketFlow 聊天查询客户端" if args.chat else "TicketFlow 直接查询工具客户端")
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
                result = client.post(
                    "/agent/v1/chat", json={"message": message, "sessionId": session_id}
                )
                body = result.json()
                if result.status_code == 200:
                    session_id = body["data"]["sessionId"]
                print(json.dumps(body, ensure_ascii=False, indent=2))
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

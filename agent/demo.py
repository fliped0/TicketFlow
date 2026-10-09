"""Interactive local query client; token stays in memory and is entered without echo."""

import getpass
import json

import httpx


def main():
    print("TicketFlow 查询客户端（直接调用查询工具；不会调用模型或执行交易）")
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
        for tool in response.json()["data"]:
            print(f"{tool['name']}: {tool['description']}")
        while True:
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

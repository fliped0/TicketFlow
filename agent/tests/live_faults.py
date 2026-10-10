"""Real Java commits, TCP loss and killed Agent children with durable same-key recovery."""

import os
import socket
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]


def run_faults(api, business, wait_for, run_id, tier, session_id, token):
    records = []
    windows = ("before_post", "after_commit_hold", "response_lost", "before_local_finish")
    for operation in ("cancel", "refund"):
        for window in windows:
            records.append(
                run_window(
                    api, business, wait_for, run_id, tier, session_id, token, operation, window
                )
            )
    return records


def run_window(api, business, wait_for, run_id, tier, session_id, token, operation, window):
    records = []
    label = operation + "_" + window
    order = business(
        api,
        "POST",
        "/api/v1/orders",
        json={"tierId": tier["id"], "quantity": 1},
        headers={"Idempotency-Key": "fault_create_" + run_id + label},
    )["orderId"]
    if operation == "refund":
        business(
            api,
            "POST",
            f"/api/v1/orders/{order}/payments",
            json={},
            headers={"Idempotency-Key": "fault_pay_" + run_id + label},
        )
    before = "PENDING" if operation == "cancel" else "PAID"
    after = "CANCELLED" if operation == "cancel" else "REFUNDED"
    suffix = "cancel" if operation == "cancel" else "refunds"
    target = f"/api/v1/orders/{order}/{suffix}"
    reached, release = threading.Event(), threading.Event()
    controls = {"fault": window, "keys": [], "forwarded": []}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def handle(self):
            try:
                super().handle()
            except (ConnectionResetError, BrokenPipeError):
                pass  # Expected when the owned Agent process is killed.

        def log_message(self, *_):
            pass

        def do_GET(self):  # noqa: N802
            self.forward()

        def do_POST(self):  # noqa: N802
            self.forward()

        def disconnect(self):
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.connection.close()

        def forward(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            selected = self.command == "POST" and self.path == target
            fault = controls["fault"] if selected else None
            if selected:
                controls["keys"].append(self.headers.get("Idempotency-Key"))
            if fault == "before_post":
                reached.set()
                release.wait(12)
                self.disconnect()
                return
            headers = {
                k: v
                for k, v in self.headers.items()
                if k.lower() not in {"host", "connection", "content-length"}
            }
            try:
                with httpx.Client(trust_env=False, timeout=10) as upstream:
                    response = upstream.request(
                        self.command,
                        str(api.base_url).rstrip("/") + self.path,
                        content=raw,
                        headers=headers,
                    )
                if selected:
                    controls["forwarded"].append(response.status_code)
                if fault in {"after_commit_hold", "response_lost"}:
                    assert response.status_code == 200
                    reached.set()
                    if fault == "after_commit_hold":
                        release.wait(12)
                    self.disconnect()
                    return
                self.send_response(response.status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response.content)))
                self.end_headers()
                self.wfile.write(response.content)
            except (httpx.HTTPError, OSError):
                self.disconnect()

    proxy = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    proxy.daemon_threads = True
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    with socket.socket() as candidate:
        candidate.bind(("127.0.0.1", 0))
        port = candidate.getsockname()[1]
    env = os.environ.copy()
    for name in tuple(env):
        if name.startswith(("TF_DB_", "TF_ADMIN_", "TF_JWT_")):
            del env[name]
    env.update(
        TF_AGENT_MODEL_MODE="disabled",
        TF_AGENT_JAVA_URL=f"http://127.0.0.1:{proxy.server_port}",
        TF_AGENT_CONFIRMATION_DB=str(ROOT / f".tools/fault-{run_id}-{label}/state.sqlite3"),
    )
    children = []

    def start(fault=False):
        cmd = [
            sys.executable,
            str(Path(__file__).with_name("fault_agent.py")),
            "--port",
            str(port),
        ]
        if fault:
            cmd.append("--exit-before-finish")
        with (ROOT / f".tools/fault-{run_id}-{label}.log").open("ab") as log:
            child = subprocess.Popen(
                cmd,
                cwd=ROOT / "agent",
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        children.append(child)
        wait_for(f"http://127.0.0.1:{port}/health", child)
        return child

    def stop(child):
        if child.poll() is None:
            child.kill()
        child.wait(timeout=5)

    try:
        child = start(window == "before_local_finish")
        with httpx.Client(
            base_url=f"http://127.0.0.1:{port}",
            trust_env=False,
            timeout=15,
            headers={"Authorization": f"Bearer {token}"},
        ) as client:
            confirmation = business(
                client,
                "POST",
                "/agent/v1/confirmations",
                json={"operation": operation, "orderId": order},
            )
            identifier = confirmation["confirmationId"]
            path = f"/agent/v1/confirmations/{identifier}"
            assert business(api, "GET", f"/api/v1/orders/{order}")["status"] == before

            def execute():
                try:
                    return client.post(path + "/execute", json={"approved": True})
                except httpx.HTTPError:
                    return None

            if window in {"before_post", "after_commit_hold"}:
                with ThreadPoolExecutor(max_workers=1) as pool:
                    future = pool.submit(execute)
                    assert reached.wait(8), "Fault window not reached"
                    stop(child)
                    release.set()
                    future.result(timeout=5)
            else:
                response = execute()
                if window == "response_lost":
                    assert response is not None
                    assert response.json()["data"]["state"] == "UNKNOWN"
                else:
                    assert child.wait(timeout=5) == 77 and response is None
                stop(child)
            actual = business(api, "GET", f"/api/v1/orders/{order}")
            committed = window != "before_post"
            assert actual["status"] == (after if committed else before)
            controls["fault"] = None
            time.sleep(2.2)  # Confirmation TTL and execution lease have expired.
            start()
            pending = business(client, "GET", path)
            assert pending["state"] == "UNKNOWN"
            result = business(client, "POST", path + "/recover", json={})
            assert result["state"] == "SUCCEEDED"
            assert result["result"]["replayed"] is committed
            assert len(set(controls["keys"])) == 1 and controls["keys"][0]
            attempts = len(controls["keys"])
            repeated = business(client, "POST", path + "/execute", json={"approved": True})
            assert repeated["result"] == result["result"]
            assert len(controls["keys"]) == attempts
            current = business(api, "GET", f"/api/v1/orders/{order}")
            assert current["status"] == after
            if operation == "refund":
                assert current["refund"]["refundId"] == result["result"]["data"]["refundId"]
            inventory = business(api, "GET", f"/api/v1/sessions/{session_id}/tiers")
            assert next(i for i in inventory["items"] if i["id"] == tier["id"])["available"] == 3
            records.append(
                {
                    "name": label,
                    "passed": True,
                    "sameKey": True,
                    "expiredRecovery": True,
                    "committedBeforeRecovery": committed,
                    "duplicateExtraPosts": 0,
                    "inventoryRestored": True,
                }
            )
    finally:
        release.set()
        for child in children:
            stop(child)
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=2)
        current = business(api, "GET", f"/api/v1/orders/{order}")
        if current["status"] in {"PENDING", "PAID"}:
            cleanup = "cancel" if current["status"] == "PENDING" else "refunds"
            business(
                api,
                "POST",
                f"/api/v1/orders/{order}/{cleanup}",
                json={},
                headers={"Idempotency-Key": "fault_cleanup_" + run_id + label},
            )
    return records[0]

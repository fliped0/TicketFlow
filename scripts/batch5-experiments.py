"""Real HTTP/JVM/MySQL experiments. Requires Python 3.12 and requests, Java 17,
MySQL client, a built jar, and the dedicated local ticketflow_test/tf_test config.
Never resets or deletes existing data. Writes a unique, scoped test corpus.
"""
import argparse
import concurrent.futures as futures
import contextlib
import ctypes
import csv
import datetime as dt
import http.server
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import socket
import socketserver
import statistics
import subprocess
import tempfile
import threading
import time
import uuid
import requests

ROOT = Path(__file__).resolve().parents[1]
HIDDEN = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError()
        data.extend(chunk)
    return bytes(data)


def packet(sock):
    header = exact(sock, 4)
    return header + exact(sock, int.from_bytes(header[:3], "little"))


class MysqlProxy:
    """Only used by dedicated test JVMs; controls actual COMMIT transport.
    No SQL or protocol payloads are logged, including authentication packets.
    """
    def __init__(self):
        self.lock = threading.Lock()
        self.armed = None
        proxy = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                upstream = socket.create_connection(("127.0.0.1", 3306))
                upstream.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                stopped = threading.Event()
                state = {"ack": None}

                def close():
                    if stopped.is_set():
                        return
                    stopped.set()
                    for sock in (self.request, upstream):
                        with contextlib.suppress(OSError):
                            sock.shutdown(socket.SHUT_RDWR)
                        with contextlib.suppress(OSError):
                            sock.close()

                def client_loop():
                    try:
                        while not stopped.is_set():
                            raw = packet(self.request)
                            payload = raw[4:]
                            # MySQL 8 COM_QUERY may prefix zero query attributes
                            # and its parameter-set count before the SQL text.
                            command = payload[1:].lstrip(b"\x00\x01").strip().lower() if payload[:1] == b"\x03" else b""
                            fault = None
                            if command == b"commit":
                                with proxy.lock:
                                    fault, proxy.armed = proxy.armed, None
                            if fault and fault["mode"] in ("kill_before_commit", "disconnect_before_commit"):
                                fault["hit"].set()
                                if fault["mode"] == "kill_before_commit":
                                    fault["release"].wait(20)
                                close()
                                return
                            if fault:
                                state["ack"] = fault
                            upstream.sendall(raw)
                    except (OSError, EOFError):
                        close()

                thread = threading.Thread(target=client_loop, daemon=True)
                thread.start()
                try:
                    while not stopped.is_set():
                        raw = packet(upstream)
                        fault = state["ack"]
                        if fault:
                            state["ack"] = None
                            require(raw[4:5] == b"\x00", "COMMIT must receive a database OK packet")
                            fault["hit"].set()
                            if fault["mode"] == "kill_after_commit":
                                fault["release"].wait(20)
                            close()
                            return
                        self.request.sendall(raw)
                except (OSError, EOFError):
                    close()
                finally:
                    close()

        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        self.server = Server(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def arm(self, mode):
        fault = {"mode": mode, "hit": threading.Event(), "release": threading.Event()}
        with self.lock:
            require(self.armed is None, "one fault at a time")
            self.armed = fault
        return fault

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class Experiments:
    def __init__(self, args):
        self.args = args
        self.config = json.loads((ROOT / "config/local/test.json").read_text(encoding="utf-8-sig"))
        require(self.config["database"] == "ticketflow_test" and self.config["username"] == "tf_test", "dedicated test account required")
        self.marker = "b5_" + uuid.uuid4().hex[:12]
        self.work = ROOT / ".tools" / self.marker
        self.work.mkdir(parents=True)
        self.deploy = self.work / "standalone"
        self.deploy.mkdir()
        self.jar = self.deploy / "ticketflow.jar"
        shutil.copy2(ROOT / "backend/target/ticketflow-0.1.0-SNAPSHOT.jar", self.jar)
        self.keys = tempfile.TemporaryDirectory(prefix="ticketflow-b5-keys-")
        subprocess.run([args.java, str(ROOT / "scripts/TestKeys.java"), self.keys.name], check=True, creationflags=HIDDEN)
        self.proxy = MysqlProxy()
        self.process = None
        self.log = None
        self.starts = 0
        self.results = {"runId": self.marker, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "environment": {
            "os": "Windows", "database": "ticketflow_test", "account": "tf_test", "java": "17",
            "applicationJvm": "-Xms256m -Xmx512m", "hikariMaximumPoolSize": 20,
            "placement": "application, MySQL and HTTP load generator on the same machine",
            "resources": subprocess.check_output(["powershell", "-NoProfile", "-Command", "[pscustomobject]@{CPU=(Get-CimInstance Win32_Processor).Name;LogicalProcessors=(Get-CimInstance Win32_Processor).NumberOfLogicalProcessors;MemoryBytes=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory} | ConvertTo-Json -Compress"], text=True, creationflags=HIDDEN).strip()
        }, "scenarios": []}
        self.report = Path(args.output).resolve()
        self.report.parent.mkdir(parents=True, exist_ok=True)
        self.actors = []
        self.results['environment']['mysql'] = self.db("SELECT VERSION(),@@innodb_buffer_pool_size,@@max_connections,@@innodb_flush_log_at_trx_commit,@@sync_binlog")[0]
        self.results['environment']['storage'] = subprocess.check_output(["powershell", "-NoProfile", "-Command", "Get-PhysicalDisk | Select-Object FriendlyName,MediaType,Size | ConvertTo-Json -Compress"], text=True, creationflags=HIDDEN).strip()
        self.results['environment']['jarSha256'] = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.results['environment']['runnerSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()

    def db(self, sql):
        env = os.environ.copy()
        env["MYSQL_PWD"] = self.config["password"]
        cmd = [self.args.mysql, "--host=127.0.0.1", "--port=3306", "--user=tf_test", "--database=ticketflow_test", "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4"]
        result = subprocess.run(cmd, input=sql, text=True, encoding="utf-8", capture_output=True, env=env, creationflags=HIDDEN)
        require(result.returncode == 0, "Test database query failed: " + result.stderr[-500:])
        return [line.split("\t") for line in result.stdout.splitlines() if line]

    def save(self, name, data):
        self.results["scenarios"].append({"name": name, "passed": True, **data})
        self.report.write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"completed": name, **data}, ensure_ascii=False), flush=True)

    def start(self, scheduler=False, proxy=True):
        require(self.process is None, "stop the owned JVM before restarting")
        port = free_port()
        self.base = f"http://127.0.0.1:{port}"
        self.starts += 1
        self.log_path = self.work / f"jvm-{self.starts}.log"
        self.log = self.log_path.open("wb")
        env = os.environ.copy()
        env.update(TF_DB_USER="tf_test", TF_DB_PASSWORD=self.config["password"], TF_DB_NAME="ticketflow_test",
                   TF_JWT_PRIVATE_KEY=str(Path(self.keys.name) / "private.pem"), TF_JWT_PUBLIC_KEY=str(Path(self.keys.name) / "public.pem"))
        url = f"jdbc:mysql://127.0.0.1:{self.proxy.port if proxy else 3306}/ticketflow_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false"
        args = [self.args.java, "-Xms256m", "-Xmx512m", "-jar", str(self.jar), f"--server.port={port}",
                f"--spring.datasource.url={url}", f"--ticketflow.expiry.enabled={str(scheduler).lower()}",
                "--ticketflow.admin.username=", "--ticketflow.admin.password="]
        began = time.perf_counter()
        self.process = subprocess.Popen(args, cwd=self.deploy, env=env, stdout=self.log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
        for _ in range(600):
            require(self.process.poll() is None, "Test JVM exited; inspect owned JVM log")
            try:
                response = requests.get(self.base + "/actuator/health", timeout=0.5)
                if response.status_code == 200:
                    return {"pid": self.process.pid, "healthReadySeconds": round(time.perf_counter() - began, 3), "scheduler": scheduler}
            except requests.RequestException:
                pass
            time.sleep(0.1)
        raise AssertionError("Test JVM did not become ready")

    def stop(self):
        if self.process:
            # Only the exact process handle created by this runner is terminated.
            self.process.kill()
            self.process.wait(timeout=15)
            self.process = None
        if self.log:
            self.log.close()
            self.log = None

    def call(self, method, path, actor=None, payload=None, key=None, base=None, session=None):
        headers = {}
        if actor:
            headers["Authorization"] = "Bearer " + actor["token"]
        if key:
            headers["Idempotency-Key"] = key
        sender = session or requests
        response = sender.request(method, (base or self.base) + path, headers=headers, json=payload, timeout=12)
        return response.status_code, response.json()

    def success(self, method, path, actor=None, payload=None, key=None, expected=200):
        status, body = self.call(method, path, actor, payload, key)
        require(status == expected, f"Unexpected HTTP {status} {body.get('code')} at {path}")
        require(body.get("traceId"), "response traceId required")
        return body

    def new_actor(self, index):
        credentials = {"username": f"{self.marker}_{index}", "password": "Test_" + uuid.uuid4().hex}
        user = self.success("POST", "/api/v1/auth/register", payload=credentials, expected=201)["data"]
        token = self.success("POST", "/api/v1/auth/login", payload=credentials)["data"]["accessToken"]
        return {"id": int(user["userId"]), "token": token, "credentials": credentials}

    def fixture(self, capacity):
        rows = self.db(f"""START TRANSACTION;
INSERT INTO tf_event(name,description,category,city,venue,status,created_at,updated_at) VALUES('{self.marker}','test','music','Beijing','Hall','ON_SALE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SET @e=LAST_INSERT_ID();
INSERT INTO tf_session(event_id,starts_at,sale_start_at,sale_end_at,freeze_at,created_at,updated_at) VALUES(@e,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 12 HOUR,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SET @s=LAST_INSERT_ID();
INSERT INTO tf_tier(session_id,name,price_fen,created_at,updated_at) VALUES(@s,'Standard',58000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SET @t=LAST_INSERT_ID();
INSERT INTO tf_stock(tier_id,capacity,available,updated_at) VALUES(@t,{capacity},{capacity},UTC_TIMESTAMP(6)); SELECT @e,@s,@t; COMMIT;""")
        return dict(zip(("event", "session", "tier"), map(int, rows[0])))

    def buy(self, actor, fixture, key=None):
        return self.success("POST", "/api/v1/orders", actor, {"tierId": str(fixture["tier"]), "quantity": 1}, key or uuid.uuid4().hex, 201)

    def reconcile(self, tier):
        sql = (ROOT / "scripts/reconcile.sql").read_text(encoding="utf-8")
        rows = self.db(f"SET @tf_reconcile_tier={int(tier)};\n" + sql)
        require(len(rows) == 1, f"Reconciliation differences for tier {tier}: {rows[1:5]}")

    def request_row(self, actor, key):
        return self.db(f"SELECT state,COALESCE(order_id,0),http_status FROM tf_request WHERE user_id={actor['id']} AND request_key='{key}'")

    def recovery(self):
        actor = self.actors[0]
        for operation in ("CREATE", "PAY", "REFUND", "CANCEL"):
            for mode in ("kill_before_commit", "kill_after_commit", "disconnect_before_commit", "disconnect_commit_ack"):
                fixture = self.fixture(1)
                original = self.buy(actor, fixture) if operation != "CREATE" else None
                order = int(original["data"]["orderId"]) if original else None
                if operation == "REFUND":
                    self.success("POST", f"/api/v1/orders/{order}/payments",actor,{},uuid.uuid4().hex)
                key = uuid.uuid4().hex
                suffix = {'PAY':'payments','REFUND':'refunds','CANCEL':'cancel'}
                path = "/api/v1/orders" if operation == "CREATE" else f"/api/v1/orders/{order}/{suffix[operation]}"
                payload = {"tierId": str(fixture["tier"]), "quantity": 1} if operation == "CREATE" else {}
                fault = self.proxy.arm(mode)
                with futures.ThreadPoolExecutor(max_workers=1) as pool:
                    pending = pool.submit(self.call, "POST", path, actor, payload, key)
                    require(fault["hit"].wait(12), "fault did not reach a real COMMIT")
                    committed = "after_commit" in mode or "commit_ack" in mode
                    if committed:
                        require(self.request_row(actor, key)[0][0] == "SUCCEEDED", "database must confirm commit before interruption")
                    else:
                        require(not self.request_row(actor, key), "uncommitted request must not be visible")
                    killed_pid = self.process.pid if mode.startswith("kill_") else None
                    if killed_pid:
                        self.stop()
                    fault["release"].set()
                    with contextlib.suppress(requests.RequestException):
                        result = pending.result(timeout=15)
                        require(result[0] >= 500, "interrupted COMMIT must not report success")
                restart = self.start() if killed_pid else None
                replay = self.success("POST", path, actor, payload, key, expected=201 if operation == "CREATE" else 200)
                require(replay["replayed"] == committed, "same key must recover the actual commit outcome")
                row = self.request_row(actor, key)
                require(len(row) == 1 and row[0][0] == "SUCCEEDED", "one terminal request required")
                self.reconcile(fixture["tier"])
                self.save(f"{operation.lower()}_{mode}", {"killedPid": killed_pid, "restart": restart, "replayed": replay["replayed"], "orderId": row[0][1], "traceId": replay["traceId"], "tierId": fixture["tier"]})

        # Real HTTP response loss, after the upstream response proves commit completed.
        for operation in ("CREATE", "PAY", "REFUND"):
            fixture = self.fixture(1)
            order = int(self.buy(actor, fixture)["data"]["orderId"]) if operation != "CREATE" else None
            if operation == "REFUND":
                self.success("POST", f"/api/v1/orders/{order}/payments",actor,{},uuid.uuid4().hex)
            path = "/api/v1/orders" if operation == "CREATE" else f"/api/v1/orders/{order}/"+('payments' if operation=='PAY' else 'refunds')
            payload = {"tierId": str(fixture["tier"]), "quantity": 1} if operation == "CREATE" else {}
            key = uuid.uuid4().hex
            captured = {}
            outer = self

            class DropResponse(http.server.BaseHTTPRequestHandler):
                def do_POST(self):
                    data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    response = requests.post(outer.base + self.path, data=data, headers={"Authorization": self.headers["Authorization"], "Idempotency-Key": self.headers["Idempotency-Key"], "Content-Type": "application/json"}, timeout=12)
                    captured.update(status=response.status_code, body=response.json())
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)

                def log_message(self, *args):
                    pass

            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), DropResponse)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                try:
                    self.call("POST", path, actor, payload, key, base=f"http://127.0.0.1:{server.server_port}")
                    raise AssertionError("HTTP response must be lost")
                except requests.RequestException:
                    pass
                require(captured["status"] == (201 if operation == "CREATE" else 200), "upstream committed success required")
                replay = self.success("POST", path, actor, payload, key, expected=captured["status"])
                require(replay["replayed"] and replay["data"] == captured["body"]["data"], "lost response must replay original result")
                self.reconcile(fixture["tier"])
                self.save(f"{operation.lower()}_http_response_loss", {"tierId": fixture["tier"], "orderId": replay["data"]["orderId"], "traceId": replay["traceId"]})
            finally:
                server.shutdown()
                server.server_close()

        fixture = self.fixture(101)
        with futures.ThreadPoolExecutor(max_workers=8) as pool:
            bought = list(pool.map(lambda a: self.buy(a, fixture), self.actors[:101]))
        ids = [int(b["data"]["orderId"]) for b in bought]
        poisoned = ids[0]
        self.stop()
        stopped_at = time.perf_counter()
        self.db(f"UPDATE tf_order SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE id IN ({','.join(map(str, ids))}); DELETE FROM tf_purchase_slot WHERE order_id={poisoned}; UPDATE tf_user SET enabled=FALSE WHERE id={self.actors[1]['id']};")
        ready = self.start(scheduler=True)
        recovered_at = time.perf_counter()
        normal_ids = ",".join(map(str, ids[1:]))
        closed = 0
        while time.perf_counter() - recovered_at < 60:
            closed = int(self.db(f"SELECT COUNT(*) FROM tf_order WHERE id IN ({normal_ids}) AND status='CLOSED'")[0][0])
            if closed == 100:
                break
            time.sleep(0.2)
        require(closed == 100, "100 recoverable expired orders must close within 60 seconds of readiness")
        elapsed = time.perf_counter() - recovered_at
        require(self.db(f"SELECT status FROM tf_order WHERE id={poisoned}")[0][0] == "PENDING", "poisoned order must remain pending")
        self.db(f"INSERT INTO tf_purchase_slot(user_id,session_id,order_id) VALUES({actor['id']},{fixture['session']},{poisoned}); UPDATE tf_user SET enabled=TRUE WHERE id={self.actors[1]['id']};")
        deadline = time.perf_counter() + 30
        while time.perf_counter() < deadline:
            if self.db(f"SELECT status FROM tf_order WHERE id={poisoned}")[0][0] == "CLOSED":
                break
            time.sleep(0.2)
        require(self.db(f"SELECT status FROM tf_order WHERE id={poisoned}")[0][0] == "CLOSED", "next scan must recover corrected poisoned order")
        time.sleep(11)
        self.reconcile(fixture["tier"])
        self.save("startup_100_expired_and_poison_retry", {"ready": ready, "stoppedToReadySeconds": round(recovered_at-stopped_at, 3), "readyTo100ClosedSeconds": round(elapsed, 3), "tierId": fixture["tier"], "disabledOwnerRecovered": True, "duplicateScansReleaseOnce": True})
        require(recovered_at-stopped_at+elapsed <= 60, "recovery including startup must meet the 60-second target")
        self.stop()
        self.start(proxy=False)

    def contention(self):
        for round_number in range(1, 4):
            fixture = self.fixture(100)
            barrier = threading.Barrier(100)
            lock = threading.Lock()
            next_index = 0
            timings, outcomes, traces = [], [], []
            maximum_active, active = 0, 0
            began = time.perf_counter()

            def worker():
                nonlocal next_index, maximum_active, active
                with requests.Session() as session:
                    barrier.wait()
                    while True:
                        with lock:
                            index = next_index
                            next_index += 1
                            if index >= 1000:
                                return
                            active += 1
                            maximum_active = max(maximum_active, active)
                            timings.append(time.perf_counter())
                        key = uuid.uuid4().hex
                        actor = self.actors[index]
                        result = None
                        initial_errors = 0
                        attempts = 0
                        try:
                            while time.perf_counter() - began < 60 and attempts < 4:
                                attempts += 1
                                try:
                                    result = self.call("POST", "/api/v1/orders", actor, {"tierId": str(fixture["tier"]), "quantity": 1}, key, session=session)
                                    if result[0] < 500:
                                        break
                                except requests.RequestException:
                                    result = None
                                initial_errors += 1
                                time.sleep(0.05)
                            with lock:
                                outcomes.append({"status": result[0] if result else 0, "code": result[1].get("code") if result else "UNKNOWN", "attempts": attempts, "initialSystemOrTransportErrors": initial_errors})
                                if result:
                                    traces.append(result[1]["traceId"])
                        finally:
                            with lock:
                                active -= 1

            with futures.ThreadPoolExecutor(max_workers=100) as pool:
                list(pool.map(lambda _: worker(), range(100)))
            elapsed = time.perf_counter()-began
            dispatch_span = max(timings)-min(timings)
            success = sum(o["status"] == 201 for o in outcomes)
            sold_out = sum(o["status"] == 409 and o["code"] == "SOLD_OUT" for o in outcomes)
            require(len(outcomes) == 1000 and success == 100 and sold_out == 900, "100 orders and 900 sold-out outcomes required")
            require(dispatch_span <= 10 and elapsed <= 60, "contention dispatch/convergence window not met")
            require(int(self.db(f"SELECT COUNT(*) FROM tf_order WHERE session_id={fixture['session']}")[0][0]) == 100, "exactly 100 database orders required")
            self.reconcile(fixture["tier"])
            self.save(f"contention_round_{round_number}", {"users": 1000, "workers": 100, "capacity": 100, "success": success, "soldOut": sold_out, "peakActiveHttp": maximum_active, "initialDispatchSpanSeconds": round(dispatch_span, 3), "totalSeconds": round(elapsed, 3), "initialSystemOrTransportErrors": sum(o["initialSystemOrTransportErrors"] for o in outcomes), "attempts": sum(o["attempts"] for o in outcomes), "tierId": fixture["tier"], "traceSamples": traces[:5]})

    def corpus(self):
        # White-box DATA-PERF preparation; measured business operations use real HTTP.
        events = ",".join(f"('{self.marker}_perf_{i}','performance corpus','music','{self.marker}','Hall','ON_SALE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))" for i in range(1000))
        self.db("INSERT INTO tf_event(name,description,category,city,venue,status,created_at,updated_at) VALUES" + events)
        event_ids = [int(r[0]) for r in self.db(f"SELECT id FROM tf_event WHERE city='{self.marker}' ORDER BY id")]
        sessions = ",".join(f"({event},UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 12 HOUR,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))" for event in event_ids for _ in range(3))
        self.db("INSERT INTO tf_session(event_id,starts_at,sale_start_at,sale_end_at,freeze_at,created_at,updated_at) VALUES" + sessions)
        session_ids = [int(r[0]) for r in self.db(f"SELECT s.id FROM tf_session s JOIN tf_event e ON e.id=s.event_id WHERE e.city='{self.marker}' ORDER BY s.id")]
        tiers = ",".join(f"({session},'Tier_{i}',58000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))" for session in session_ids for i in range(3))
        self.db("INSERT INTO tf_tier(session_id,name,price_fen,created_at,updated_at) VALUES" + tiers)
        self.db(f"INSERT INTO tf_stock(tier_id,capacity,available,updated_at) SELECT t.id,1000000,1000000,UTC_TIMESTAMP(6) FROM tf_tier t JOIN tf_session s ON s.id=t.session_id JOIN tf_event e ON e.id=s.event_id WHERE e.city='{self.marker}'")
        rows = self.db(f"SELECT s.event_id,t.session_id,t.id FROM tf_tier t JOIN tf_session s ON s.id=t.session_id JOIN tf_event e ON e.id=s.event_id WHERE e.city='{self.marker}' AND t.name='Tier_0' ORDER BY t.session_id")
        require(len(event_ids) == 1000 and len(session_ids) == 3000 and len(rows) == 3000, "DATA-PERF shape required")
        self.save("performance_corpus", {"events": 1000, "sessions": 3000, "tiers": 9000, "preparation": "direct SQL test fixtures; not counted as API success"})
        return [dict(zip(("event", "session", "tier"), map(int, row))) for row in rows]

    def load(self, kind, corpus):
        warmup, measurement = 60, 300
        started = time.perf_counter()
        finish = started + warmup + measurement
        barrier = threading.Barrier(20)
        lock = threading.Lock()
        samples = {}
        progress = {"nextPrint": started + 30}

        def record(name, begin, status, code):
            if begin < started + warmup or begin >= finish:
                return
            with lock:
                samples.setdefault(name, []).append((1000*(time.perf_counter()-begin), status, code))

        def measured(session, name, method, path, actor=None, payload=None, key=None):
            begin = time.perf_counter()
            try:
                status, body = self.call(method, path, actor, payload, key, session=session)
                record(name, begin, status, body.get("code"))
                return status, body
            except requests.RequestException:
                record(name, begin, 0, "TRANSPORT_ERROR")
                return 0, {}

        def worker(index):
            actor = self.actors[index]
            sequence = 0
            with requests.Session() as session:
                barrier.wait()
                while time.perf_counter() < finish:
                    fixture = corpus[sequence % len(corpus)]
                    if kind == "query":
                        queries = (("event_list", f"/api/v1/events?city={self.marker}&page={(sequence%50)+1}&size=20"),
                                   ("event_detail", f"/api/v1/events/{fixture['event']}"),
                                   ("session_list", f"/api/v1/events/{fixture['event']}/sessions"),
                                   ("tier_list", f"/api/v1/sessions/{fixture['session']}/tiers"))
                        name, path = queries[sequence % len(queries)]
                        measured(session, name, "GET", path)
                    else:
                        # Give each worker a distinct session, then refund successful payment
                        # outside latency samples to permit sustained eligible purchases.
                        fixture = corpus[index]
                        key = uuid.uuid4().hex
                        status, body = measured(session, "create", "POST", "/api/v1/orders", actor, {"tierId": str(fixture["tier"]), "quantity": 1}, key)
                        if status == 201:
                            order = body["data"]["orderId"]
                            paid, _ = measured(session, "pay", "POST", f"/api/v1/orders/{order}/payments", actor, {}, uuid.uuid4().hex)
                            if paid == 200:
                                result, _ = self.call("POST", f"/api/v1/orders/{order}/refunds", actor, {}, uuid.uuid4().hex, session=session)
                                require(result == 200, "performance cleanup refund failed")
                            else:
                                raise AssertionError("payment did not succeed; retain failure evidence")
                        else:
                            raise AssertionError("create did not succeed; eligible load condition failed")
                    sequence += 1
                    with lock:
                        now = time.perf_counter()
                        if now >= progress["nextPrint"]:
                            print(json.dumps({"running": kind, "elapsedSeconds": round(now-started), "measuredRequests": sum(map(len,samples.values()))}), flush=True)
                            progress["nextPrint"] = now + 30

        with futures.ThreadPoolExecutor(max_workers=20) as pool:
            list(pool.map(worker, range(20)))
        stats = {}
        for name, values in samples.items():
            durations = sorted(v[0] for v in values)
            errors = sum(v[1] == 0 or v[1] >= 500 for v in values)
            rejects = sum(400 <= v[1] < 500 for v in values)
            p95 = durations[math.ceil(len(durations)*0.95)-1]
            stats[name] = {"requests": len(values), "success": sum(200 <= v[1] < 300 for v in values), "businessRejections": rejects, "systemOrTransportErrors": errors, "errorRate": errors/len(values), "p95Ms": round(p95, 3), "meanMs": round(statistics.mean(durations), 3), "maxMs": round(max(durations), 3), "requestsPerSecond": round(len(values)/measurement, 3)}
        sample_path = self.report.with_name(self.report.stem + '-' + kind + '-samples.csv')
        with sample_path.open('w', newline='', encoding='utf-8') as output:
            writer = csv.writer(output)
            writer.writerow(('endpoint','latencyMs','httpStatus','code'))
            for name, values in samples.items():
                writer.writerows((name, round(latency,6),status,code) for latency,status,code in values)
        self.results["lastLoad"] = {"kind": kind, "stats": stats}
        self.report.write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")
        require(stats and all(s["success"]==s["requests"] and s["errorRate"] < 0.01 and s["p95Ms"] <= (500 if kind == "query" else 1000) for s in stats.values()), "performance SLA or eligible load condition not met; see measured evidence")
        if kind == "trade":
            for fixture in corpus[:20]:
                self.reconcile(fixture["tier"])
        self.save(f"performance_{kind}", {"workers": 20, "warmupSeconds": warmup, "measurementSeconds": measurement, "elapsedSeconds": round(time.perf_counter()-started, 3), "stats": stats, "tradeCleanup": "full refund after each successful payment, excluded from endpoint latency samples" if kind == "trade" else None})

    def run(self):
        ready = self.start(proxy=self.args.phase in ("all", "recovery"))
        self.save("standalone_deployment_health", ready)
        print("Preparing independent authenticated users outside measurements", flush=True)
        with futures.ThreadPoolExecutor(max_workers=8) as pool:
            self.actors = list(pool.map(self.new_actor, range(1000 if self.args.phase in ("all", "contention") else 101)))
        self.save("authenticated_users_prepared", {"count": len(self.actors), "registrationAndLogin": "real HTTP before all timing windows"})
        self.deployment_smoke()
        if self.args.phase in ("all", "recovery"):
            self.recovery()
        if self.args.phase in ("all", "contention"):
            self.contention()
        if self.args.phase in ("all", "recovery", "expiry"):
            self.normal_expiry()
        if self.args.phase in ("all", "performance"):
            if self.args.phase == 'all':
                self.stop()
                self.start(proxy=False)
            corpus = self.corpus()
            self.load("query", corpus)
            # Refresh measured users after the six-minute query run, outside timings.
            for actor in self.actors[:20]:
                actor["token"] = self.success("POST", "/api/v1/auth/login", payload=actor["credentials"])["data"]["accessToken"]
            self.load("trade", corpus)
        self.results["completedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        self.results["passed"] = True
        self.report.write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")

    def normal_expiry(self):
        fixture = self.fixture(1)
        actor = self.actors[0]
        order = int(self.buy(actor,fixture)['data']['orderId'])
        self.stop()
        self.start(scheduler=True,proxy=False)
        self.db(f"UPDATE tf_order SET expires_at=UTC_TIMESTAMP(6)+INTERVAL 2 SECOND WHERE id={order}")
        deadline = time.perf_counter()+60
        while time.perf_counter()<deadline:
            row = self.db(f"SELECT status,TIMESTAMPDIFF(MICROSECOND,expires_at,updated_at)/1000000 FROM tf_order WHERE id={order}")[0]
            if row[0]=='CLOSED':
                require(0<=float(row[1])<=60,'normal expiry must close within 60 seconds of its deadline')
                self.reconcile(fixture['tier'])
                self.save('normal_running_expiry_deadline',{'orderId':order,'tierId':fixture['tier'],'expiryToClosedSeconds':float(row[1])})
                return
            time.sleep(0.2)
        raise AssertionError('normal expiry was not closed within 60 seconds')

    def deployment_smoke(self):
        admin = self.actors[100]
        self.db(f"UPDATE tf_user SET role='ADMIN' WHERE id={admin['id']}")
        admin["token"] = self.success("POST", "/api/v1/auth/login", payload=admin["credentials"])["data"]["accessToken"]
        event = self.success("POST", "/api/v1/admin/events", admin, {"name": self.marker+"_http", "description": "standalone deployment", "category": "music", "city": "Beijing", "venue": "Hall"}, expected=201)["data"]["id"]
        now = dt.datetime.now(dt.timezone.utc)
        sale = now + dt.timedelta(seconds=25)
        times = {"saleStartAt": sale.isoformat(), "saleEndAt": (now+dt.timedelta(hours=1)).isoformat(), "startsAt": (now+dt.timedelta(hours=2)).isoformat()}
        session = self.success("POST", f"/api/v1/admin/events/{event}/sessions", admin, times, expected=201)["data"]["id"]
        tier = self.success("POST", f"/api/v1/admin/sessions/{session}/tiers", admin, {"name": "Standard", "priceFen": 58000, "capacity": 1}, expected=201)["data"]["id"]
        self.success("PUT", f"/api/v1/admin/events/{event}/status", admin, {"status": "ON_SALE", "expectedVersion": 0})
        self.success("GET", f"/api/v1/events/{event}")
        while dt.datetime.now(dt.timezone.utc) <= sale:
            time.sleep(0.1)
        order = self.buy(self.actors[0], {"tier": int(tier)})["data"]["orderId"]
        paid = self.success("POST", f"/api/v1/orders/{order}/payments", self.actors[0], {}, uuid.uuid4().hex)
        detail = self.success("GET", f"/api/v1/admin/orders?orderId={order}", admin)["data"]["items"][0]
        require(detail["status"] == "PAID" and detail["payment"]["paymentId"] == paid["data"]["paymentId"], "management detail must show the paid deployment order")
        self.success('GET','/actuator/env',admin,expected=404)
        self.reconcile(int(tier))
        self.save("standalone_admin_publish_purchase_payment", {"eventId": event, "sessionId": session, "tierId": tier, "orderId": order, "traceId": paid["traceId"], "directory": "copied executable jar in isolated standalone directory, disposable RSA keys"})

    def close(self):
        self.stop()
        self.proxy.close()
        self.keys.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("all", "recovery", "contention", "performance", "expiry"), default="all")
    parser.add_argument("--java", default="java.exe")
    parser.add_argument("--mysql", default="mysql.exe")
    parser.add_argument("--output", default=".tools/batch5-experiments.json")
    args = parser.parse_args()
    experiment = Experiments(args)
    try:
        if os.name == "nt":
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
        experiment.run()
    except BaseException as error:
        experiment.results["passed"] = False
        experiment.results["failure"] = type(error).__name__ + ": " + str(error)
        experiment.report.write_text(json.dumps(experiment.results, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
    finally:
        experiment.close()
        if os.name == "nt":
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)

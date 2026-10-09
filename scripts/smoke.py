#!/usr/bin/env python3
"""Build first with mvn verify. Runs real JVM apps against a deterministic MCP HTTP fixture.

No third-party Python packages. Credentials are generated per run, never printed, and
redacted from collected process logs. --demo leaves a populated local workspace running.
"""
import argparse
import contextlib
import http.server
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOGS = ROOT / "run" / "test-logs"
SCHEMA = {"type": "object", "properties": {"orderId": {"type": "string"}},
          "required": ["orderId"], "additionalProperties": False}


class Fixture:
    def __init__(self):
        self.tools = [
            {"name": "query", "description": "Query order", "inputSchema": SCHEMA},
            {"name": "refund", "description": "Refund order", "inputSchema": SCHEMA},
        ]
        self.partial_failure = False
        self.call_error = False
        self.calls = []
        self.credential = "Bearer " + secrets.token_urlsafe(36)
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass

            def respond(self, status, data=None):
                encoded = b"" if data is None else json.dumps(data).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def do_GET(self):
                self.respond(405)

            def do_DELETE(self):
                self.respond(204)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                if self.headers.get("Authorization") != fixture.credential:
                    self.respond(401, {"error": "missing_service_credential"})
                    return
                method = body.get("method")
                if method == "notifications/initialized":
                    self.respond(202)
                    return
                params = body.get("params", {})
                if method == "initialize":
                    result = {"protocolVersion": params.get("protocolVersion", "2025-11-25"),
                              "capabilities": {"tools": {"listChanged": False}},
                              "serverInfo": {"name": "test-orders", "version": "1.0"}}
                elif method == "tools/list":
                    offset = int(params.get("cursor", "0"))
                    if fixture.partial_failure and offset:
                        self.respond(503, {"error": "temporary_failure"})
                        return
                    result = {"tools": fixture.tools[offset:offset + 1]}
                    if offset + 1 < len(fixture.tools):
                        result["nextCursor"] = str(offset + 1)
                elif method == "tools/call":
                    fixture.calls.append(params)
                    result = {"content": [{"type": "text", "text": json.dumps(params)}],
                              "isError": fixture.call_error}
                elif method == "ping":
                    result = {}
                else:
                    self.respond(400, {"error": "unsupported_method"})
                    return
                self.respond(200, {"jsonrpc": "2.0", "id": body.get("id"), "result": result})

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_port

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def http(base, path, method="GET", token=None, data=None, headers=None):
    body = None if data is None else json.dumps(data).encode()
    outgoing = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if token:
        outgoing["Authorization"] = "Bearer " + token
    outgoing.update(headers or {})
    request = urllib.request.Request(base + path, data=body, headers=outgoing, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        try:
            decoded = json.loads(raw) if raw else None
        except ValueError:
            decoded = raw.decode(errors="replace")
        return response.status, decoded


class Environment:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.fixture = Fixture()
        self.tokens = {name: secrets.token_urlsafe(36) for name in ["admin", "read", "discovery", "signing", "alice", "bob"]}
        ports = set()
        while len(ports) < 4:
            ports.add(free_port())
        self.ports = dict(zip(["admin", "discovery", "runtime", "gateway"], sorted(ports)))
        self.urls = {name: "http://127.0.0.1:" + str(port) for name, port in self.ports.items()}
        self.processes = {}
        self.handles = []
        self.env = dict(os.environ,
            ADMIN_TOKEN=self.tokens["admin"], CONTROL_READ_TOKEN=self.tokens["read"],
            DISCOVERY_TOKEN=self.tokens["discovery"], RUNTIME_SIGNING_SECRET=self.tokens["signing"],
            DEMO_ALICE_TOKEN=self.tokens["alice"], DEMO_BOB_TOKEN=self.tokens["bob"],
            CONTROL_URL=self.urls["admin"], RUNTIME_URL=self.urls["runtime"],
            IDENTITY_MODE="demo", SPRING_PROFILES_ACTIVE="demo", BIND_ADDRESS="127.0.0.1",
            CATALOG_FILE=str(self.directory / "catalog.json"), ALLOWED_UPSTREAM_HOSTS="127.0.0.1",
            SNAPSHOT_REFRESH_MS="200", SNAPSHOT_MAX_AGE_MS="3000", DISCOVERY_INTERVAL_MS="600000",
            MCP_UPSTREAM_ORDERS=self.fixture.credential)
        LOGS.mkdir(parents=True, exist_ok=True)

    def start(self, name):
        jar = ROOT / "apps" / (name + "-server") / "target" / (name + "-server-0.1.0-SNAPSHOT.jar")
        if not jar.exists():
            raise RuntimeError("Build the Maven project before running integration or demo.")
        log = open(LOGS / (name + ".log"), "a", encoding="utf-8")
        self.handles.append(log)
        self.processes[name] = subprocess.Popen(["java", "-Xms64m", "-Xmx256m", "-jar", str(jar)],
            env=dict(self.env, SERVER_PORT=str(self.ports[name])), stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            if self.processes[name].poll() is not None:
                raise RuntimeError(name + " terminated during startup; see sanitized application logs")
            try:
                if http(self.urls[name], "/actuator/health")[0] == 200:
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.25)
        raise RuntimeError(name + " did not become ready")

    def stop(self, name):
        process = self.processes.pop(name, None)
        if process:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def close(self):
        for name in list(self.processes):
            self.stop(name)
        self.fixture.close()
        for handle in self.handles:
            handle.close()
        for path in LOGS.glob("*.log"):
            contents = path.read_text(errors="replace")
            for secret in list(self.tokens.values()) + [self.fixture.credential]:
                contents = contents.replace(secret, "[redacted]")
            path.write_text(contents, encoding="utf-8")

    def admin(self, path, data=None, method=None):
        status, result = http(self.urls["admin"], path, method or ("POST" if data is not None else "GET"),
                              self.tokens["admin"], data)
        assert status == 200, (path, status, result)
        return result

    def sync(self):
        status, result = http(self.urls["discovery"], "/internal/sync", "POST",
                              self.tokens["discovery"], {"serviceId": "orders"})
        assert status == 200, (status, result)
        return result

    def snapshot(self):
        return self.admin("/api/snapshot")

    def rpc(self, user, method, params=None, group="orders", headers=None):
        request = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}
        return http(self.urls["gateway"], "/mcp/" + group, "POST", self.tokens.get(user), request, headers)

    def tools(self, user, names):
        """Poll only read-only listing; never automatically retry a business invocation."""
        deadline = time.monotonic() + 12
        last = None
        stable = 0
        while time.monotonic() < deadline:
            status, result = self.rpc(user, "tools/list")
            last = (status, result)
            observed = {t["name"] for t in result.get("result", {}).get("tools", [])} if status == 200 else None
            stable = stable + 1 if observed == set(names) else 0
            if stable >= 3:
                return result
            time.sleep(.25)
        raise AssertionError(("visible tool set did not converge", names, last))

    def grant(self, grant_id, subject, tool, group="orders", role=False):
        return self.admin("/api/grants", {"id": grant_id, "tenant": "demo",
            "subjectType": "ROLE" if role else "USER", "subject": subject, "issuer": "demo",
            "groupId": group, "toolId": tool["id"], "toolRevision": tool["revision"]})

    def publish(self, tool, protected=False):
        return self.admin("/api/tools/" + tool["id"] + "/publish", {"resourceProtected": protected})


def seed(env):
    env.admin("/api/services", {"id": "orders", "tenant": "demo",
        "endpoint": "http://127.0.0.1:" + str(env.fixture.port) + "/mcp",
        "transport": "streamable-http", "credentialRef": "MCP_UPSTREAM_ORDERS", "enabled": True})
    assert env.sync()["orders"]["outcome"] == "success"
    initial = env.snapshot()
    assert len(initial["tools"]) == 2, initial
    assert all(t["state"] == "PENDING" for t in initial["tools"].values())
    by_name = {t["upstreamName"]: t for t in initial["tools"].values()}
    env.admin("/api/groups", {"id": "orders", "tenant": "demo", "title": "订单运营",
        "description": "完整组含查询和退款；隐藏能力不得进入用户说明",
        "tools": {"find_order": by_name["query"]["id"], "refund_order": by_name["refund"]["id"]}})
    env.tools("alice", [])
    for tool in by_name.values():
        env.publish(tool)
    env.tools("alice", [])  # Publishing and grouping do not grant access.
    env.grant("alice-query", "alice", by_name["query"])
    env.grant("reviewer-refund", "reviewer", by_name["refund"], role=True)
    env.tools("alice", ["find_order"])
    env.tools("bob", ["refund_order"])
    return by_name


def verify(env, by_name):
    alice_init = env.rpc("alice", "initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
        "clientInfo": {"name": "integration", "version": "1"}})
    assert alice_init[0] == 200 and "refund" not in json.dumps(alice_init[1]), alice_init
    assert env.rpc(None, "tools/list")[0] == 401
    assert env.rpc("alice", "tools/list", headers={"Origin": "https://unapproved.example"})[0] == 403
    assert env.rpc("alice", "tools/list", headers={"X-Mcp-Execution-Permit": "attacker-supplied"})[0] == 200
    direct = http(env.urls["runtime"], "/mcp/orders", "POST", None,
                  {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert direct[0] == 401, direct
    assert http(env.urls["admin"], "/api/snapshot", token=env.tokens["read"])[0] == 401
    assert http(env.urls["admin"], "/internal/discovery", "POST", env.tokens["admin"], {})[0] == 401

    before = len(env.fixture.calls)
    for name in ["refund_order", "refund", "guessed_tool"]:
        result = env.rpc("alice", "tools/call", {"name": name, "arguments": {"orderId": "123"}})
        assert result[0] == 403, result
    assert len(env.fixture.calls) == before
    invalid = env.rpc("alice", "tools/call", {"name": "find_order", "arguments": {"orderId": 42}})
    assert invalid[0] == 400, invalid
    assert len(env.fixture.calls) == before
    allowed = env.rpc("alice", "tools/call", {"name": "find_order", "arguments": {"orderId": "123"}})
    assert allowed[0] == 200 and not allowed[1]["result"]["isError"], allowed
    assert len(env.fixture.calls) == before + 1 and env.fixture.calls[-1]["name"] == "query"
    env.fixture.call_error = True
    business_error = env.rpc("alice", "tools/call", {"name": "find_order", "arguments": {"orderId": "missing"}})
    assert business_error[0] == 200 and business_error[1]["result"]["isError"], business_error
    env.fixture.call_error = False

    env.admin("/api/groups", {"id": "another", "tenant": "demo", "title": "另一能力组", "description": "",
        "tools": {"query_again": by_name["query"]["id"]}})
    env.tools("alice", ["find_order"])
    cross = env.rpc("alice", "tools/call", {"name": "query_again", "arguments": {"orderId": "123"}}, "another")
    assert cross[0] == 403, cross
    env.admin("/api/grants/alice-query", method="DELETE")
    env.tools("alice", [])
    assert env.rpc("alice", "tools/call", {"name": "find_order", "arguments": {"orderId": "123"}})[0] == 403

    # Partial pagination is a failed observation, never a deletion.
    before = env.snapshot()
    env.fixture.partial_failure = True
    assert env.sync()["orders"]["outcome"] == "failed"
    assert env.snapshot() == before
    env.fixture.partial_failure = False
    env.fixture.tools.append({"name": "export", "description": "Export orders", "inputSchema": SCHEMA})
    assert env.sync()["orders"]["outcome"] == "success"
    added = next(t for t in env.snapshot()["tools"].values() if t["upstreamName"] == "export")
    assert added["state"] == "PENDING"
    env.tools("bob", ["refund_order"])

    # An old revision grant cannot become valid merely by re-publishing a new definition.
    env.fixture.tools[1] = dict(env.fixture.tools[1], description="Refund orders with revised semantics")
    assert env.sync()["orders"]["outcome"] == "success"
    changed = env.snapshot()["tools"][by_name["refund"]["id"]]
    assert changed["state"] == "PENDING" and changed["revision"] > by_name["refund"]["revision"]
    env.publish(changed)
    env.tools("bob", [])
    env.grant("reviewer-refund", "reviewer", changed, role=True)
    env.tools("bob", ["refund_order"])
    env.admin("/api/tools/" + changed["id"] + "/disable", {})
    env.tools("bob", [])
    assert env.rpc("bob", "tools/call", {"name": "refund_order", "arguments": {"orderId": "123"}})[0] == 403
    env.fixture.tools = [tool for tool in env.fixture.tools if tool["name"] != "refund"]
    assert env.sync()["orders"]["outcome"] == "success"
    assert env.snapshot()["tools"][changed["id"]]["state"] == "REMOVED"

    # Resource access is an extension point that denies by default.
    env.publish(by_name["query"], protected=True)
    protected = env.snapshot()["tools"][by_name["query"]["id"]]
    env.grant("alice-query", "alice", protected)
    env.tools("alice", ["find_order"])
    assert env.rpc("alice", "tools/call", {"name": "find_order", "arguments": {"orderId": "other"}})[0] == 403

    # Persistence survives a management process restart, stale policies eventually stop admission.
    saved = env.snapshot()
    env.stop("admin")
    time.sleep(3.5)
    assert env.rpc("alice", "tools/list")[0] == 503
    env.start("admin")
    assert env.snapshot() == saved
    env.tools("alice", ["find_order"])
    print("PASS: discovery, publication, identity isolation, scoped grants, forwarding, revocation, changes and lease expiry")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--demo", action="store_true", help="Keep the seeded local environment running")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="mcp-governance-") as directory:
        env = Environment(directory)
        try:
            for name in ["admin", "runtime", "discovery", "gateway"]:
                env.start(name)
            by_name = seed(env)
            if args.demo:
                access_file = ROOT / "run" / "demo-access.json"
                access_file.parent.mkdir(exist_ok=True)
                descriptor = os.open(access_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(descriptor, "w") as handle:
                    json.dump({"urls": env.urls, "tokens": {key: env.tokens[key] for key in ["admin", "alice", "bob"]}},
                              handle, indent=2)
                print("Local demo ready. Open the admin URL recorded in run/demo-access.json.")
                print("Demo credentials are in that local ignored file. Press Ctrl+C to stop and erase the demo.")
                try:
                    while True:
                        time.sleep(1)
                finally:
                    access_file.unlink(missing_ok=True)
            else:
                verify(env, by_name)
        finally:
            env.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

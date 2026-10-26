"""Exercise Redis loss through two independent SharedPresenceDevServer JVMs.

Run after test-compile and dependency:build-classpath with test scope and output
target/recovery-process-classpath.txt. Uses only disposable loopback fixtures.
"""

import argparse
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def free_port():
    """Reserve a candidate loopback port; startup detects any bind race."""
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(port, user, method, path, body=None):
    """Use the fixture's mock session through the actual HTTP boundary."""
    separator = "&" if "?" in path else "?"
    url = f"http://127.0.0.1:{port}/qqq/v1/qbit/collaboration{path}{separator}user={user}"
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Cookie": f"sessionUUID={user}-recovery-processes",
        "Content-Type": "application/json",
    })
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def require(status, result):
    """Fail with fixture-only response evidence when transport differs."""
    assert result[0] == status, result
    return result[1]


def settle(ports, leases):
    """Renew on the opposite node until both nodes report both people."""
    deadline = time.monotonic() + 60
    sequence = 0
    while time.monotonic() < deadline:
        sequence += 1
        for index, (user, lease) in enumerate(leases.items()):
            result = request(ports[1 - index], user, "POST", "/presence/leases/renew", {
                "connectionId": lease, "table": "person", "pk": "1",
                "mode": "VIEWING", "sequence": sequence,
            })
            assert result[0] in (200, 503), result
            if result[0] == 503:
                assert "recovering" in result[1]["message"], result
        snapshots = [request(port, "alice", "GET", "/presence?table=person&pk=1") for port in ports]
        if all(status == 200 for status, _ in snapshots):
            assert all(len(body["viewers"]) == 2 for _, body in snapshots), snapshots
            return
        assert all(status == 503 for status, _ in snapshots), snapshots
        time.sleep(1)
    raise AssertionError("Two-process recovery exceeded 60 seconds")


def main():
    """Own and clean up the exact child JVMs and disposable Redis container."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--frontend-repo", type=Path)
    parser.add_argument("--browser-profile", choices=("chromium", "firefox", "webkit", "mobile", "tablet"), default="chromium")
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = repo / "target" / ("recovery-processes-" + uuid.uuid4().hex[:10])
    output.mkdir()
    classpath = os.pathsep.join([str(repo / "target/test-classes"), str(repo / "target/classes"),
                                (repo / "target/recovery-process-classpath.txt").read_text().strip()])
    if args.frontend_repo:
        shutil.copytree(args.frontend_repo.resolve() / "out", output / "next-dashboard")
        classpath = str(output) + os.pathsep + classpath
    container = "qqq-recovery-" + uuid.uuid4().hex[:12]
    processes, logs = [], []
    try:
        subprocess.run(["docker", "run", "-d", "--name", container, "-p", "127.0.0.1::6379", "redis:7.4-alpine"], check=True, capture_output=True)
        binding = subprocess.check_output(["docker", "port", container, "6379"], text=True).strip()
        redis_port = int(binding.rsplit(":", 1)[1])
        ports = [free_port(), free_port()]
        for port in ports:
            argfile = output / f"node-{port}.args"
            argfile.write_text('-Dcollab.dev.frontend=' + str(bool(args.frontend_repo)).lower() + '\n-cp\n"' + classpath.replace("\\", "/") + '"\n'
                               'com.kingsrook.qbits.collaboration.devserver.SharedPresenceDevServer\n'
                               f'{port}\n{redis_port}\n', encoding="utf-8")
            log = (output / f"node-{port}.log").open("w")
            logs.append(log)
            processes.append(subprocess.Popen([args.java, "@" + str(argfile)], cwd=repo,
                                              stdout=log, stderr=subprocess.STDOUT,
                                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))
        deadline = time.monotonic() + 60
        for port, process in zip(ports, processes):
            while f"SHARED_PRESENCE_READY {port}" not in (output / f"node-{port}.log").read_text(errors="replace"):
                assert process.poll() is None, f"Node exited; see {output}"
                assert time.monotonic() < deadline, f"Startup timed out; see {output}"
                time.sleep(0.2)
        if args.frontend_repo:
            subprocess.run(["node", str(repo / "docs/acceptance/presence_recovery_browser.mjs"),
                            str(args.frontend_repo.resolve()), str(ports[0]), str(ports[1]), container, str(output), args.browser_profile], check=True)
            return
        def join():
            """Acquire each person's new lease on a different node."""
            return {user: require(200, request(port, user, "POST", "/presence/leases", {
                "table": "person", "pk": "1"}))["connectionId"] for port, user in zip(ports, ("alice", "bob"))}
        original = join()
        settle(ports, original)
        subprocess.run(["docker", "exec", container, "redis-cli", "FLUSHDB"], check=True, capture_output=True)
        for port in ports:
            require(503, request(port, "alice", "GET", "/presence?table=person&pk=1"))
            for user, lease in original.items():
                error = require(409, request(port, user, "POST", "/presence/leases/renew", {
                    "connectionId": lease, "table": "person", "pk": "1", "mode": "VIEWING", "sequence": 1000}))
                assert error["code"] == "PRESENCE_LEASE_EXPIRED", error
        replacement = join()
        assert all(replacement[user] != original[user] for user in original)
        settle(ports, replacement)
        print(f"PASS: two independent JVMs recovered two viewers after Redis loss. Logs: {output}")
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        for log in logs:
            log.close()
        subprocess.run(["docker", "rm", "-f", container], capture_output=True, check=False)


if __name__ == "__main__":
    main()

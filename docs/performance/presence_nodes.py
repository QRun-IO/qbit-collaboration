"""Bounded HTTP presence workload across independent, disposable JVMs.

This measures loopback HTTP service latency, not browser propagation or a
production capacity limit. Run test-compile and dependency:build-classpath with
test scope and output target/capacity-classpath.txt before invoking this script.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import uuid


def port():
    """Choose a loopback port; child startup detects a competing bind."""
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(node, user, path, body=None, tab=0):
    """Use real HTTP and the explicit mock identity; no tenant parameter."""
    separator = "&" if "?" in path else "?"
    req = urllib.request.Request(
        f"http://127.0.0.1:{node}/qqq/v1/qbit/collaboration{path}{separator}user={user}",
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Cookie": f"sessionUUID={user}-capacity-{tab}"})
    with urllib.request.urlopen(req, timeout=10) as response:
        if response.status != 200:
            raise AssertionError(f"Unexpected status {response.status}")
        return json.load(response)


def distribution(values):
    """Nearest-rank milliseconds, with no discarded slow or failed samples."""
    ordered = sorted(values)
    return {"samples": len(ordered), **{
        name: ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]
        for name, fraction in (("p50_ms", .5), ("p95_ms", .95), ("p99_ms", .99), ("max_ms", 1))}}


def main():
    """Own child JVMs/container and retain results even when a check fails."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--nodes", type=int, default=3)
    parser.add_argument("--tenants", type=int, default=2)
    parser.add_argument("--users", type=int, default=6)
    parser.add_argument("--tabs", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    for name, lower, upper in (("nodes", 2, 8), ("tenants", 2, 8), ("users", 2, 100),
                               ("tabs", 2, 8), ("rounds", 1, 1000), ("workers", 1, 64)):
        if not lower <= getattr(args, name) <= upper:
            parser.error(f"{name} must be {lower}..{upper}")
    repo = Path(__file__).resolve().parents[2]
    output = repo / "target" / ("capacity-nodes-" + uuid.uuid4().hex[:10])
    output.mkdir(parents=True)
    classpath = os.pathsep.join([str(repo / "target/test-classes"), str(repo / "target/classes"),
                                (repo / "target/capacity-classpath.txt").read_text().strip()])
    processes, logs = [], []
    container = "qqq-capacity-" + uuid.uuid4().hex[:12]
    result = {"status": "FAIL", "parameters": vars(args), "scope": "loopback HTTP, mock identities, memory records"}
    try:
        subprocess.run(["docker", "run", "-d", "--name", container, "-p", "127.0.0.1::6379",
                        "redis:7.4-alpine"], check=True, capture_output=True)
        redis_port = int(subprocess.check_output(["docker", "port", container, "6379"], text=True).strip().rsplit(":", 1)[1])
        nodes = [port() for _ in range(args.nodes)]
        if len(set(nodes)) != len(nodes):
            raise RuntimeError("Port allocation collision; no nodes started")
        for node in nodes:
            argfile = output / f"node-{node}.args"
            argfile.write_text(f'-Xmx384m\n-Dcollab.capacity.tenants={args.tenants}\n-Dcollab.capacity.users={args.users}\n'
                               '-cp\n"' + classpath.replace("\\", "/") + '"\n'
                               'com.kingsrook.qbits.collaboration.devserver.CapacityPresenceDevServer\n'
                               f'{node}\n{redis_port}\n', encoding="utf-8")
            log = (output / f"node-{node}.log").open("w")
            logs.append(log)
            processes.append(subprocess.Popen([args.java, "@" + str(argfile)], cwd=repo,
                                              stdout=log, stderr=subprocess.STDOUT,
                                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))
        deadline = time.monotonic() + 120
        for node, process in zip(nodes, processes):
            while f"CAPACITY_PRESENCE_READY {node}" not in (output / f"node-{node}.log").read_text(errors="replace"):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"Node {node} failed startup; inspect {output}")
                time.sleep(.2)
        actors = []
        for tenant in range(args.tenants):
            for user in range(args.users):
                for tab in range(args.tabs):
                    identity = f"load-t{tenant}-u{user}"
                    record = str(1 + tab % 2)
                    node = nodes[len(actors) % len(nodes)]
                    lease = request(node, identity, "/presence/leases", {"table": "person", "pk": record}, tab)["connectionId"]
                    actors.append((identity, record, tab, lease, user))
        renewals, snapshots = [], []

        def renew(item, sequence):
            """Keep each tab sequential while moving its writes between nodes."""
            index, (identity, record, tab, lease, user) = item
            before = time.perf_counter()
            request(nodes[(index + sequence) % len(nodes)], identity, "/presence/leases/renew",
                    {"connectionId": lease, "table": "person", "pk": record,
                     "mode": "EDITING" if user < 2 else "VIEWING", "sequence": sequence}, tab)
            return (time.perf_counter() - before) * 1000

        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.workers) as workers:
            for sequence in range(1, args.rounds + 1):
                renewals.extend(workers.map(lambda item: renew(item, sequence), enumerate(actors)))
                for tenant in range(args.tenants):
                    expected = {f"load-t{tenant}-u{user}" for user in range(args.users)}
                    for node in nodes:
                        for record in ("1", "2"):
                            before = time.perf_counter()
                            viewers = request(node, f"load-t{tenant}-u0", f"/presence?table=person&pk={record}")["viewers"]
                            snapshots.append((time.perf_counter() - before) * 1000)
                            actual = {viewer["userId"] for viewer in viewers}
                            if actual != expected or len(viewers) != len(expected):
                                raise AssertionError(f"Tenant isolation/deduplication mismatch: {actual} != {expected}")
                            if sum(viewer["mode"] == "EDITING" for viewer in viewers) != 2:
                                raise AssertionError("Expected exactly two editors")
        elapsed = time.perf_counter() - started
        result.update(status="PASS", seconds=elapsed, renewals=distribution(renewals),
                      snapshots=distribution(snapshots), renewal_throughput=len(renewals) / elapsed,
                      node_pids=[process.pid for process in processes])
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        for log in logs:
            log.close()
        cleanup = subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        result["cleanup"] = cleanup.returncode == 0 and all(process.poll() is not None for process in processes)
        (output / "result.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2))
        print(output)
        if result["status"] == "PASS" and not result["cleanup"]:
            raise RuntimeError("Workload passed but owned resource cleanup failed")


if __name__ == "__main__":
    main()

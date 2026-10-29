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
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def port():
    """Choose a loopback port; child startup detects a competing bind."""
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(node, user, path, body=None, tab=0, method=None, expected=200):
    """Use real HTTP and the explicit mock identity; no tenant parameter."""
    separator = "&" if "?" in path else "?"
    req = urllib.request.Request(
        f"http://127.0.0.1:{node}/qqq/v1/qbit/collaboration{path}{separator}user={user}",
        data=None if body is None else json.dumps(body).encode(),
        method=method,
        headers={"Content-Type": "application/json",
                 "Cookie": f"sessionUUID={user}-capacity-{tab}"})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        if error.code == expected:
            with error:
                return json.load(error)
        with error:
            detail = error.read(4096).decode(errors="replace")
        raise RuntimeError(f"HTTP {error.code} node={node} user={user} path={path}: {detail}") from error
    with response:
        if response.status != expected:
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
    parser.add_argument("--node-image", help="Optional Linux Java21 image; isolate Redis traffic on an owned Docker network")
    parser.add_argument("--nodes", type=int, default=3)
    parser.add_argument("--tenants", type=int, default=2)
    parser.add_argument("--users", type=int, default=6)
    parser.add_argument("--tabs", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--crash-round", type=int, default=0, help="Kill the first node before this round; zero disables")
    parser.add_argument("--churn-every", type=int, default=0, help="Leave/rejoin the last user's tabs every N rounds; zero disables")
    parser.add_argument("--pause-redis", action="store_true", help="After the workload, freeze owned Redis and verify bounded failures/recovery")
    args = parser.parse_args()
    for name, lower, upper in (("nodes", 2, 8), ("tenants", 2, 8), ("users", 2, 100),
                               ("tabs", 2, 8), ("rounds", 1, 1000), ("workers", 1, 64)):
        if not lower <= getattr(args, name) <= upper:
            parser.error(f"{name} must be {lower}..{upper}")
    if args.crash_round and not 2 <= args.crash_round <= args.rounds:
        parser.error("crash-round must be zero or 2..rounds")
    if args.churn_every and not 1 <= args.churn_every <= args.rounds:
        parser.error("churn-every must be zero or 1..rounds")
    repo = Path(__file__).resolve().parents[2]
    output = repo / "target" / ("capacity-nodes-" + uuid.uuid4().hex[:10])
    output.mkdir(parents=True)
    classpath = os.pathsep.join([str(repo / "target/test-classes"), str(repo / "target/classes"),
                                (repo / "target/capacity-classpath.txt").read_text().strip()])
    processes, logs = [], []
    container = "qqq-capacity-" + uuid.uuid4().hex[:12]
    network = container + "-net"
    node_containers = []
    network_created = False
    result = {"status": "FAIL", "parameters": vars(args), "scope": "loopback HTTP, mock identities, memory records"}
    try:
        network_args = []
        if args.node_image:
            result["node_image_id"] = subprocess.check_output(
                ["docker", "image", "inspect", args.node_image, "--format", "{{.Id}}"], text=True).strip()
            subprocess.run(["docker", "network", "create", network], check=True, capture_output=True)
            network_created = True
            network_args = ["--network", network]
            dependencies = output / "dependencies"
            dependencies.mkdir()
            linux_classpath = ["/fixture/target/test-classes", "/fixture/target/classes"]
            for index, dependency in enumerate((repo / "target/capacity-classpath.txt").read_text().strip().split(os.pathsep)):
                destination = dependencies / f"{index}.jar"
                shutil.copyfile(dependency, destination)
                linux_classpath.append("/fixture/" + destination.relative_to(repo).as_posix())
            classpath = ":".join(linux_classpath)
        subprocess.run(["docker", "run", "-d", "--name", container, "-p", "127.0.0.1::6379",
                        *network_args,
                        "redis:7.4-alpine", "redis-server", "--slowlog-log-slower-than", "1000",
                        "--slowlog-max-len", "128", "--latency-monitor-threshold", "1"], check=True, capture_output=True)
        redis_port = int(subprocess.check_output(["docker", "port", container, "6379"], text=True).strip().rsplit(":", 1)[1])
        nodes = [port() for _ in range(args.nodes)]
        if len(set(nodes)) != len(nodes):
            raise RuntimeError("Port allocation collision; no nodes started")
        for node in nodes:
            argfile = output / f"node-{node}.args"
            connection_properties = (f"-Dcollab.capacity.redisHost={container}\n-Dcollab.dev.bindAddress=0.0.0.0\n"
                                     if args.node_image else "")
            argfile.write_text(f'-Xmx384m\n-Dcollab.capacity.tenants={args.tenants}\n-Dcollab.capacity.users={args.users}\n'
                               + connection_properties +
                               '-cp\n"' + classpath.replace("\\", "/") + '"\n'
                               'com.kingsrook.qbits.collaboration.devserver.CapacityPresenceDevServer\n'
                               + (f'8080\n6379\n' if args.node_image else f'{node}\n{redis_port}\n'), encoding="utf-8")
            log = (output / f"node-{node}.log").open("w")
            logs.append(log)
            command = [args.java, "@" + str(argfile)]
            if args.node_image:
                node_container = f"{container}-node-{node}"
                node_containers.append(node_container)
                command = ["docker", "run", "--rm", "--name", node_container, "--network", network,
                           "-p", f"127.0.0.1:{node}:8080", "--mount", f"type=bind,source={repo},target=/fixture,readonly",
                           args.node_image, "java", "@/fixture/" + argfile.relative_to(repo).as_posix()]
            processes.append(subprocess.Popen(command, cwd=repo,
                                              stdout=log, stderr=subprocess.STDOUT,
                                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))
        deadline = time.monotonic() + 120
        for node, process in zip(nodes, processes):
            while "CAPACITY_PRESENCE_READY " not in (output / f"node-{node}.log").read_text(errors="replace"):
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

        def check_rooms(excluded=None):
            """Check every tenant/record on every surviving node, including absence."""
            for tenant in range(args.tenants):
                expected_users = {f"load-t{tenant}-u{user}" for user in range(args.users) if user != excluded}
                for node in nodes:
                    for record in ("1", "2"):
                        before = time.perf_counter()
                        viewers = request(node, f"load-t{tenant}-u0", f"/presence?table=person&pk={record}")["viewers"]
                        snapshots.append((time.perf_counter() - before) * 1000)
                        actual = {viewer["userId"] for viewer in viewers}
                        if actual != expected_users or len(viewers) != len(expected_users):
                            raise AssertionError(f"Tenant isolation/deduplication mismatch: {actual} != {expected_users}")
                        if sum(viewer["mode"] == "EDITING" for viewer in viewers) != sum(user != excluded for user in range(2)):
                            raise AssertionError("Unexpected editor count")

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
                result["current_round"] = sequence
                if sequence == args.crash_round:
                    crashed = nodes.pop(0)
                    if args.node_image:
                        subprocess.run(["docker", "kill", node_containers[0]], check=True, capture_output=True)
                    else:
                        processes[0].kill()
                    processes[0].wait(timeout=10)
                    result["crash"] = {"round": sequence, "http_port": crashed,
                                       "surviving_nodes": len(nodes), "process_exit": processes[0].returncode}
                renewals.extend(workers.map(lambda item: renew(item, sequence), enumerate(actors)))
                check_rooms()
                if args.churn_every and sequence % args.churn_every == 0:
                    departing = [(index, actor) for index, actor in enumerate(actors) if actor[4] == args.users - 1]
                    for index, (identity, record, tab, lease, user) in departing:
                        request(nodes[index % len(nodes)], identity, "/presence",
                                {"connectionId": lease, "table": "person", "pk": record, "sequence": sequence + 1}, tab, method="DELETE")
                        response = request(nodes[(index + 1) % len(nodes)], identity, "/presence/leases/renew",
                                           {"connectionId": lease, "table": "person", "pk": record,
                                            "mode": "VIEWING", "sequence": sequence + 2}, tab, expected=409)
                        if response.get("code") != "PRESENCE_LEASE_EXPIRED":
                            raise AssertionError("Departed lease was not rejected as expired")
                    check_rooms(excluded=args.users - 1)
                    for index, (identity, record, tab, lease, user) in departing:
                        replacement = request(nodes[(index + 1) % len(nodes)], identity, "/presence/leases",
                                              {"table": "person", "pk": record}, tab)["connectionId"]
                        if replacement == lease:
                            raise AssertionError("Rejoin reused departed authority")
                        actors[index] = (identity, record, tab, replacement, user)
                        renew((index, actors[index]), sequence)
                    check_rooms()
                    result["churn_cycles"] = result.get("churn_cycles", 0) + 1
                    result["departed_leases_rejected"] = result.get("departed_leases_rejected", 0) + len(departing)
                result["completed_rounds"] = sequence
        if args.pause_redis:
            failures = []

            def stalled_renew(item):
                """A frozen authority must fail explicitly, never acknowledge a write."""
                index, (identity, record, tab, lease, user) = item
                before = time.perf_counter()
                response = request(nodes[index % len(nodes)], identity, "/presence/leases/renew",
                                   {"connectionId": lease, "table": "person", "pk": record,
                                    "mode": "EDITING" if user < 2 else "VIEWING", "sequence": args.rounds + 1},
                                   tab, expected=503)
                if "viewers" in response or "temporarily unavailable" not in response.get("message", ""):
                    raise AssertionError("Stalled authority did not return the expected unavailable response")
                duration = (time.perf_counter() - before) * 1000
                if duration > 8000:
                    raise AssertionError("Unavailable response exceeded the eight-second fixture bound")
                return duration

            subprocess.run(["docker", "pause", container], check=True, capture_output=True)
            try:
                with ThreadPoolExecutor(max_workers=64) as burst:
                    failures.extend(burst.map(stalled_renew, enumerate(actors)))
                for node in nodes:
                    response = request(node, "load-t0-u0", "/presence?table=person&pk=1", expected=503)
                    if "viewers" in response:
                        raise AssertionError("Stalled authority was presented as a room snapshot")
            finally:
                subprocess.run(["docker", "unpause", container], check=True, capture_output=True)
            subprocess.run(["docker", "exec", container, "redis-cli", "PING"], check=True, capture_output=True, timeout=10)
            for item in enumerate(actors):
                renew(item, args.rounds + 2)
            check_rooms()
            result["redis_stall"] = {"rejected_renewals": len(failures), "failed_snapshot_nodes": len(nodes),
                                     "response_times": distribution(failures), "same_leases_recovered": True}
        elapsed = time.perf_counter() - started
        result.update(status="PASS", seconds=elapsed, renewals=distribution(renewals),
                      snapshots=distribution(snapshots), renewal_throughput=len(renewals) / elapsed,
                      node_pids=[process.pid for process in processes],
                      pid_kind="docker-client" if args.node_image else "JVM", node_containers=node_containers)
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        failure_active = sys.exc_info()[0] is not None
        try:
            slow = json.loads(subprocess.check_output(
                ["docker", "exec", container, "redis-cli", "--json", "SLOWLOG", "GET", "128"], text=True, timeout=10))
            result["redis_slow_commands"] = [{"unix_time": entry[1], "duration_us": entry[2], "command": entry[3][0]}
                                             for entry in slow]
            result["redis_latency_events"] = json.loads(subprocess.check_output(
                ["docker", "exec", container, "redis-cli", "--json", "LATENCY", "LATEST"], text=True, timeout=10))
            for section in ("cpu", "memory", "commandstats", "stats"):
                value = subprocess.check_output(["docker", "exec", container, "redis-cli", "INFO", section], text=True, timeout=10)
                (output / f"redis-{section}.txt").write_text(value)
                parsed = dict(line.split(":", 1) for line in value.splitlines() if ":" in line and not line.startswith("#"))
                if section == "memory":
                    result["redis_memory"] = {key: int(parsed[key]) for key in
                                              ("used_memory", "used_memory_peak", "used_memory_rss")}
                if section == "cpu":
                    result["redis_cpu_seconds_since_start"] = {key: float(parsed[key]) for key in
                                                               ("used_cpu_sys", "used_cpu_user")}
        except (subprocess.SubprocessError, ValueError, KeyError) as error:
            result["redis_diagnostic_error"] = type(error).__name__
        node_cleanup = True
        for node_container in node_containers:
            removed = subprocess.run(["docker", "rm", "-f", node_container], capture_output=True)
            if removed.returncode != 0:
                remaining = subprocess.run(["docker", "ps", "-aq", "--filter", f"name=^{node_container}$"], capture_output=True, text=True)
                node_cleanup = node_cleanup and remaining.returncode == 0 and not remaining.stdout.strip()
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
        resources = []
        for node_log in sorted(output.glob("node-*.log")):
            samples = [json.loads(line.split("CAPACITY_METRIC ", 1)[1])
                       for line in node_log.read_text(errors="replace").splitlines()
                       if line.startswith("CAPACITY_METRIC ")]
            if samples:
                first, last = samples[0], samples[-1]
                wall_ms = last["uptimeMillis"] - first["uptimeMillis"]
                cpu_ns = last["cpuNanos"] - first["cpuNanos"]
                resources.append({"node_log": node_log.name, "samples": len(samples),
                                  "observed_wall_ms": wall_ms,
                                  "cpu_seconds": cpu_ns / 1e9 if first["cpuNanos"] >= 0 else None,
                                  "cpu_core_equivalents": cpu_ns / (wall_ms * 1e6) if wall_ms > 0 and first["cpuNanos"] >= 0 else None,
                                  **{key + "_sampled_max": max(sample[key] for sample in samples)
                                     for key in ("heapUsedBytes", "heapCommittedBytes", "poolActive", "poolIdle", "poolWaiting", "poolMaximum")},
                                  "residentBytes_sampled_max": max(sample["residentBytes"] for sample in samples)
                                  if all(sample.get("residentBytes", -1) >= 0 for sample in samples) else None})
                if any(sample["poolActive"] > sample["poolMaximum"] for sample in samples):
                    result.update(status="FAIL", error="Sampled pool active count exceeded configured maximum")
        result["resources"] = resources
        if result["status"] == "PASS" and args.node_image and any(node["residentBytes_sampled_max"] is None for node in resources):
            result.update(status="FAIL", error="Linux resident-memory samples missing")
        if result["status"] == "PASS" and any(key not in result for key in ("redis_memory", "redis_cpu_seconds_since_start")):
            result.update(status="FAIL", error="Redis memory/CPU diagnostics missing")
        if result["status"] == "PASS" and len(resources) != args.nodes:
            result.update(status="FAIL", error="Missing resource samples for a node")
        if result["status"] == "PASS" and args.pause_redis and not any(node["poolWaiting_sampled_max"] > 0 for node in resources):
            result.update(status="FAIL", error="Redis stall did not demonstrate pool waiters")
        cleanup = subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        network_cleanup = True
        if network_created:
            network_cleanup = subprocess.run(["docker", "network", "rm", network], capture_output=True).returncode == 0
        result["cleanup"] = cleanup.returncode == 0 and node_cleanup and network_cleanup and all(process.poll() is not None for process in processes)
        (output / "result.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2))
        print(output)
        if result["status"] == "PASS" and not result["cleanup"]:
            raise RuntimeError("Workload passed but owned resource cleanup failed")
        if result["status"] == "FAIL" and not failure_active:
            raise RuntimeError(result.get("error", "Capacity resource validation failed"))


if __name__ == "__main__":
    main()

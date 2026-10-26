"""Restore a disposable Redis image while both fixture JVMs are stopped.

This verifies the controlled-restore protocol, not production orchestration.
Requires the same compiled test classpath as presence_recovery_processes.py.
"""

import argparse
import os
from pathlib import Path
import socket
import subprocess
import time
import uuid

from presence_recovery_processes import free_port, request, require, settle


def main():
    """Own a Redis fixture and two generations of independently running nodes."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--repo", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = repo / "target" / ("restore-processes-" + uuid.uuid4().hex[:10])
    output.mkdir()
    classpath = os.pathsep.join([str(repo / "target/test-classes"), str(repo / "target/classes"),
                                (repo / "target/recovery-process-classpath.txt").read_text().strip()])
    container = "qqq-restore-" + uuid.uuid4().hex[:12]
    processes, logs = [], []
    ports = [free_port(), free_port()]

    def redis(*command):
        """Run commands only against this owned, disposable Redis container."""
        return subprocess.check_output(["docker", "exec", container, "redis-cli", *command], text=True).strip()

    def stop_nodes():
        """Require process termination and closed old listeners before restore."""
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        processes.clear()
        for port in ports:
            with socket.socket() as connection:
                connection.settimeout(1)
                assert connection.connect_ex(("127.0.0.1", port)) != 0, "Old listener remains open"

    def start_nodes(generation, redis_port):
        """Supply the shared generation outside the Redis image, on both JVMs."""
        for port in ports:
            argfile = output / f"{generation}-{port}.args"
            argfile.write_text(f'-Dcollab.dev.recoveryGeneration={generation}\n-cp\n"'
                               + classpath.replace("\\", "/") + '"\n'
                               'com.kingsrook.qbits.collaboration.devserver.SharedPresenceDevServer\n'
                               f'{port}\n{redis_port}\n', encoding="utf-8")
            logfile = output / f"{generation}-{port}.log"
            log = logfile.open("w")
            logs.append(log)
            processes.append(subprocess.Popen([args.java, "@" + str(argfile)], cwd=repo,
                                              stdout=log, stderr=subprocess.STDOUT,
                                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))
        deadline = time.monotonic() + 60
        for port, process in zip(ports, processes):
            logfile = output / f"{generation}-{port}.log"
            while f"SHARED_PRESENCE_READY {port}" not in logfile.read_text(errors="replace"):
                assert process.poll() is None, f"Node exited; see {output}"
                assert time.monotonic() < deadline, f"Startup timed out; see {output}"
                time.sleep(0.2)

    def join():
        """Issue each person's lease on a separate node."""
        return {user: require(200, request(port, user, "POST", "/presence/leases", {
            "table": "person", "pk": "1"}))["connectionId"] for port, user in zip(ports, ("alice", "bob"))}

    def reject_old(leases, sequence):
        """Restored lease authority must fail on both new-generation nodes."""
        for port in ports:
            for user, lease in leases.items():
                error = require(409, request(port, user, "POST", "/presence/leases/renew", {
                    "connectionId": lease, "table": "person", "pk": "1",
                    "mode": "VIEWING", "sequence": sequence}))
                assert error["code"] == "PRESENCE_LEASE_EXPIRED", error

    try:
        # DEBUG RELOAD is enabled only on this disposable loopback fixture.
        subprocess.run(["docker", "run", "-d", "--name", container, "-p", "127.0.0.1::6379",
                        "redis:7.4-alpine", "redis-server", "--enable-debug-command", "yes", "--save", ""],
                       check=True, capture_output=True)
        binding = subprocess.check_output(["docker", "port", container, "6379"], text=True).strip()
        redis_port = int(binding.rsplit(":", 1)[1])
        start_nodes("before-restore", redis_port)
        original = join()
        settle(ports, original)
        last_renewal = time.monotonic()
        for port, (user, lease) in zip(ports, original.items()):
            require(200, request(port, user, "POST", "/presence/leases/renew", {
                "connectionId": lease, "table": "person", "pk": "1",
                "mode": "VIEWING", "sequence": 100}))
        assert redis("SAVE") == "OK"
        before = int(redis("DBSIZE"))
        assert before > 0
        stop_nodes()
        assert redis("FLUSHDB") == "OK"
        assert redis("DBSIZE") == "0"
        assert redis("DEBUG", "RELOAD", "NOSAVE") == "OK"
        assert int(redis("DBSIZE")) > 0, "Saved Redis image was not restored"
        start_nodes("restored-" + uuid.uuid4().hex, redis_port)
        reject_old(original, 1000)
        # Avoid a false pass caused only by the old 45-second lease expiring.
        assert time.monotonic() - last_renewal < 40, "Restore exceeded the live-lease proof window"
        for port in ports:
            result = require(503, request(port, "alice", "GET", "/presence?table=person&pk=1"))
            assert "recovering" in result["message"], result
        replacement = join()
        assert all(replacement[user] != original[user] for user in original)
        settle(ports, replacement)
        reject_old(original, 1001)
        print(f"PASS: restored Redis image cannot authorize old leases on either new-generation JVM. Logs: {output}")
    finally:
        try:
            stop_nodes()
        finally:
            for log in logs:
                log.close()
            subprocess.run(["docker", "rm", "-f", container], capture_output=True, check=False)


if __name__ == "__main__":
    main()

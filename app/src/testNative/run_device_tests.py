"""Run against the isolated test daemon on port 23333 (client UID 19998).

The probe owns UID 19999's temporary cgroups and refuses an existing UID group.
It never freezes an installed application or overwrites user configuration.
"""
import concurrent.futures
import json
from pathlib import Path
import shlex
import subprocess

PROBE = "/data/local/tmp/silence-control-probe"
PACKAGE = "cn.himpqblog.silence.migrationprobe"


def root(command, data=None):
    return subprocess.run(["adb", "shell", "su -c " + shlex.quote(command)], input=data,
                          capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15)


def request(payload, uid=0):
    data = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    result = root(f"{PROBE} --client {uid} 23333", data + "\n")
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return json.loads(result.stdout)


checks = []


def check(name, condition, response):
    checks.append({"name": name, "passed": bool(condition), "response": response})
    print(name, bool(condition), flush=True)


spawn = root(PROBE + " --spawn")
if spawn.returncode:
    raise RuntimeError(spawn.stderr or spawn.stdout)
pid = int(spawn.stdout.strip())
uptime = float(root("cat /proc/uptime").stdout.split()[0])
base = {"command": "SET_FREEZE", "package": PACKAGE, "uid": 19999,
        "targets": ["ALL"], "deadlineMs": int(uptime * 1000) + 600000}
try:
    for uid, expected in [(0, True), (19998, True), (1000, True), (2000, False), (19997, False)]:
        result = request("PING", uid)
        check(f"authenticate_uid_{uid}", (result.get("protocol") == 8) == expected, result)
    globals_config = {key: "0" for key in ["silence_hook_enabled", "silence_freeze_hook_enabled", "silence_performance_hook_enabled", "silence_process_debug_log_enabled"]}
    globals_config.update(silence_hook_poll_interval_seconds="30", silence_process_priority_rules_b64="")
    config = {"globals": globals_config, "rules": {"apps": {}}, "externalPath": "", "externalDefault": "balance", "externalModes": {}}
    result = request({"command": "SYNC_CONFIG", "config": config})
    check("configuration_sync", result.get("success"), result)
    result = request({"command": "SYNC_CONFIG", "config": config})
    check("configuration_deduplicated", result.get("success") and result.get("changed") is False, result)
    result = request({"command": "SYNC_CONFIG", "config": {**config, "externalDefault": "invalid"}})
    check("invalid_configuration_rejected", result.get("code") == "invalid_external_modes", result)
    result = request({"command": "GET_PROCESSES", "uid": 19999, "package": PACKAGE})
    check("identity", result.get("success") and any(p["pid"] == pid for p in result["processes"]), result)
    result = request({**base, "freeze": True, "version": 100})
    check("freeze", result.get("success") and result.get("writes") == 1, result)
    result = request({"command": "GET_PROCESSES", "uid": 19999, "package": PACKAGE})
    check("frozen_readback", result.get("success") and result["processes"][0]["frozen"], result)
    result = request({**base, "freeze": False, "version": 200, "prelaunch": True})
    check("thaw", result.get("success") and result.get("writes") == 1, result)
    result = request({**base, "freeze": True, "version": 150})
    check("stale_freeze_rejected", result.get("code") == "stale_request", result)
    result = request({**base, "freeze": True, "version": 300})
    check("launch_protection", result.get("code") == "launch_protected", result)
    result = request({**base, "freeze": True, "version": 400, "deadlineMs": 1})
    check("expired_request", result.get("code") == "request_expired", result)
    for name, uid in [("system_uid_rejected", 1000), ("self_uid_rejected", 19998)]:
        result = request({**base, "freeze": True, "version": 500, "uid": uid})
        check(name, result.get("success") is False, result)
    result = request({"command": "OOM", "uid": 19999, "package": PACKAGE, "pid": pid, "processName": PACKAGE})
    check("oom_read", result.get("success") and result.get("before") == result.get("after"), result)
    adj = result["before"]
    result = request({"command": "OOM", "uid": 19999, "package": PACKAGE, "pid": pid, "processName": PACKAGE, "adj": adj})
    check("oom_write_verified", result.get("success") and result.get("after") == adj, result)
    result = request({"command": "OOM", "uid": 19999, "package": "cn.wrong.package", "pid": pid, "processName": PACKAGE, "adj": adj})
    check("wrong_identity_rejected", result.get("success") is False, result)
    result = request({"command": "SYNC_CONFIG", "config": {}}, 1000)
    check("hook_config_mutation_rejected", result.get("code") == "command_not_authorized", result)
    result = request('{"command":"SET_FREEZE","uid":19999,"uid":1000}')
    check("duplicate_key_rejected", result.get("code") == "INVALID_REQUEST", result)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: request("PING"), range(12)))
    check("concurrent_connections", all(r.get("protocol") == 8 for r in results), {"count": len(results)})
    result = request("GET_STATUS")
    check("metrics_available", "control" in result and "SET_FREEZE" in result["control"]["metrics"], result["control"])
finally:
    root(f"kill -9 {pid}; rmdir /sys/fs/cgroup/apps/uid_19999/pid_{pid}; rmdir /sys/fs/cgroup/apps/uid_19999")
    output = Path(".codex/daemon-migration/native-api-tests.json")
    output.write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
if not all(c["passed"] for c in checks):
    raise SystemExit(2)
print("api_tests_passed", len(checks))

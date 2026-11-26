#!/usr/bin/env python3
"""Send commands to the dev-bridge phone from this machine (no phone interaction needed).

Usage:
    export SUPABASE_URL=https://lwmjhmmpsoxukynbfksw.supabase.co
    export SUPABASE_SERVICE_ROLE_KEY=...   # service_role key (server-side only)
    export DEVICE_ID=beed5b54-9a44-4bba-9bc3-6f4909128afa

    python3 bridge.py ping
    python3 bridge.py root_check
    python3 bridge.py shell "id; getprop ro.build.version.release"
    python3 bridge.py shell "pm list packages | wc -l"

Requires the phone to be paired + bridge enabled (see dev-bridge/README.md).
"""
import json
import os
import sys
import time
import urllib.request

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://lwmjhmmpsoxukynbfksw.supabase.co")
SERVICE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
DEVICE_ID = os.environ.get("DEVICE_ID", "beed5b54-9a44-4bba-9bc3-6f4909128afa")


def api(path, method="GET", body=None):
    req = urllib.request.Request(
        SUPABASE_URL + "/rest/v1/" + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "apikey": SERVICE_KEY,
            "Authorization": f"Bearer {SERVICE_KEY}",
            "Content-Type": "application/json",
            "Prefer": "return=representation",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def send(action, args):
    rows = api(
        "bridge_commands",
        "POST",
        {
            "device_id": DEVICE_ID,
            "action": action,
            "args": args,
            "status": "pending",
            "expires_at": "now()+2min",
        },
    )
    return rows[0]["id"]


def wait_result(cmd_id, timeout=130):
    deadline = time.time() + timeout
    while time.time() < deadline:
        rows = api(f"bridge_commands?id=eq.{cmd_id}&select=status,result")
        if rows and rows[0]["status"] in ("completed", "failed"):
            return rows[0]
        time.sleep(2)
    return {"status": "timeout", "result": None}


def main():
    if not SERVICE_KEY:
        print("Set SUPABASE_SERVICE_ROLE_KEY first.", file=sys.stderr)
        sys.exit(1)
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "ping":
        cid = send("ping", {})
    elif cmd == "root_check":
        cid = send("root_check", {})
    elif cmd == "shell" and len(sys.argv) > 2:
        cid = send("root_shell", {"command": " ".join(sys.argv[2:])})
    else:
        print("Usage: bridge.py [ping|root_check|shell <command>]", file=sys.stderr)
        sys.exit(1)
    print(f"command_id={cid} — waiting for phone…")
    res = wait_result(cid)
    print(f"status={res['status']}")
    print((res.get("result") or {}).get("message", ""))


if __name__ == "__main__":
    main()

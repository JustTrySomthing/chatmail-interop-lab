#!/usr/bin/env python3
"""Run one fresh P2P pairing attempt on two development-controlled Android devices."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
from xml.etree import ElementTree

PACKAGE = "app.p2p"
ACTIVITY = PACKAGE + "/.MainActivity"
INVITE = re.compile(r"p2p[123]:[A-Za-z0-9_-]+\Z")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\Z")


def matches(label):
    return lambda node: label in (node.get("text"), node.get("content-desc"))


class Device:
    def __init__(self, adb, serial, wait):
        self.adb = adb
        self.serial = serial
        self.wait = wait
        self.dump = "/data/local/tmp/p2p-test-" + uuid.uuid4().hex + ".xml"

    def run(self, *args, timeout=20, check=True):
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True,
                                text=True, timeout=timeout, check=False)
        if check and result.returncode:
            raise RuntimeError(f"ADB {args[0]} exited with code {result.returncode}")
        return result.stdout.strip()

    def ensure_fresh(self):
        if self.run("get-state") != "device":
            raise RuntimeError("ADB device is unavailable")
        path = self.run("shell", "pm", "path", PACKAGE, check=False)
        if path:
            raise RuntimeError("P2P is already installed. Use a fresh test device or reset a dedicated test device separately.")

    def install(self, apk):
        if self.run("install", "-g", "--no-streaming", str(apk), timeout=240).splitlines()[-1:] != ["Success"]:
            raise RuntimeError("APK installation did not return Success")
        self.run("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=60)

    def finish_setup(self, name):
        self.tap("Display name")
        self.enter(name)
        self.tap("Continue")
        ready = self.find(lambda node: any(matches(label)(node) for label in
                          ("Start chatting", "Continue with current permissions")), "the setup completion button")
        self.tap_node(ready)
        self.find(matches("Contacts"), "Contacts")

    def find(self, predicate, label):
        deadline = time.monotonic() + self.wait
        while time.monotonic() < deadline:
            try:
                self.run("shell", "rm", "-f", self.dump)
                self.run("shell", "uiautomator", "dump", self.dump)
                document = self.run("exec-out", "cat", self.dump)
                for node in ElementTree.fromstring(document).iter("node"):
                    if predicate(node):
                        return node
            except (ElementTree.ParseError, RuntimeError, subprocess.TimeoutExpired):
                pass
            time.sleep(0.6)
        raise RuntimeError(f"Timed out waiting for {label}")

    def tap_node(self, node):
        bounds = BOUNDS.fullmatch(node.get("bounds", ""))
        if not bounds:
            raise RuntimeError("Control bounds were unavailable")
        x1, y1, x2, y2 = map(int, bounds.groups())
        self.run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(self, label):
        self.tap_node(self.find(matches(label), label))

    def enter(self, text):
        self.run("shell", "input", "text", text, timeout=90)

    def back(self):
        self.run("shell", "input", "keyevent", "KEYCODE_BACK")

    def send(self, text):
        self.tap("Message")
        self.enter(text)
        self.tap("Send")

    def verify_received(self, text):
        self.find(matches(text), "the incoming test message")


def run_attempt(args, record):
    a = Device(args.adb, args.inviter, args.wait)
    b = Device(args.adb, args.recipient, args.wait)
    elapsed = time.monotonic()

    def done(stage):
        record["completed"][stage] = round(time.monotonic() - elapsed, 2)

    record["stage"] = "fresh install"
    a.ensure_fresh()
    b.ensure_fresh()
    a.install(args.apk)
    b.install(args.apk)
    done("both_installed")

    record["stage"] = "Chatmail profile setup"
    a.finish_setup("P2P-inviter")
    b.finish_setup("P2P-recipient")

    record["stage"] = "Chatmail relay connection"
    for device in (a, b):
        device.tap("Settings")
        device.find(matches("Connected to Chatmail"), "a Chatmail relay connection")
        device.back()
    done("both_chatmail_profiles_connected")

    record["stage"] = "invite"
    a.tap("Contacts")
    a.tap("Add contact")
    code = a.find(lambda n: bool(INVITE.fullmatch(n.get("text", ""))), "a complete invite code").get("text")
    b.tap("Contacts")
    b.tap("Add contact")
    b.tap("Accept invite")
    b.tap("Invite code")
    b.enter(code)
    b.tap("Connect")
    done("invite_imported")

    record["stage"] = "automatic pairing"
    a.tap("Message")
    b.tap("Message")
    done("paired")

    record["stage"] = "bidirectional text"
    token = uuid.uuid4().hex
    first, second = "p2p-a-" + token, "p2p-b-" + token
    a.send(first)
    b.verify_received(first)
    done("recipient_received")
    b.send(second)
    a.verify_received(second)
    done("inviter_received")
    record["stage"] = "complete"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--inviter", required=True, help="ADB serial of the first clean test device")
    parser.add_argument("--recipient", required=True, help="ADB serial of the second clean test device")
    parser.add_argument("--paths", required=True, help="Operator-observed topology, for example cable ISP to cellular carrier")
    parser.add_argument("--results", type=Path, required=True, help="JSONL file to append all attempts to")
    parser.add_argument("--wait", type=float, default=120, help="Time budget in seconds for each UI milestone")
    args = parser.parse_args()
    if args.inviter == args.recipient:
        parser.error("Use two different devices")
    if not args.apk.is_file() or args.wait <= 0:
        parser.error("Provide an existing APK and a positive wait budget")
    record = {"started_utc": datetime.now(timezone.utc).isoformat(), "paths": args.paths,
              "stage": "preflight", "completed": {}, "passed": False}
    try:
        run_attempt(args, record)
        record["passed"] = True
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        record["failure"] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    with args.results.open("a", encoding="utf-8") as output:
        output.write(json.dumps(record) + "\n")
    print(json.dumps(record, indent=2))
    return 0 if record["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Rebuild Android APKs and refuse completion with stale or missing artifacts.

Uses only the Python standard library. Install a copy in ~/.codex/scripts for
the global UserPromptSubmit/Stop hooks; it also works directly in any checkout.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import zipfile


CONFIG = "scripts/android-build.json"
STATE_DIR = ".gradle/codex-apk"
EXCLUDED = {".git", ".gradle", ".kotlin", ".idea", ".serena", ".codex",
            ".impeccable", "__pycache__", "build", "node_modules", "output"}
CONTINUATION = "Android APK completion check:"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + "." + str(os.getpid()) + ".tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n")
    temp.replace(path)


def read_json(path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text())


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def project_at(cwd):
    current = Path(cwd).resolve()
    for candidate in (current, *current.parents):
        if (candidate / "gradlew").is_file():
            if candidate.name == "android" and not (candidate / ".git").exists():
                repository = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                                            cwd=candidate, capture_output=True, text=True)
                if repository.returncode == 0 and Path(repository.stdout.strip()).resolve() != candidate:
                    return candidate.parent
            return candidate
        if (candidate / "android/gradlew").is_file():
            return candidate
    return None


def gradle_directory(root):
    return root if (root / "gradlew").is_file() else root / "android"


def source_files(root):
    """Include tracked and new sources, plus ignored local Gradle configuration."""
    result = subprocess.run(["git", "ls-files", "--cached", "--others",
                             "--exclude-standard", "-z"], cwd=root,
                            capture_output=True)
    if result.returncode == 0:
        names = set(os.fsdecode(result.stdout).split("\0")) - {""}
    else:
        names = set()
        for parent, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in EXCLUDED]
            names.update(str((Path(parent) / f).relative_to(root)) for f in files)
    names.update(p for p in ("local.properties", "gradle.properties",
                            "android/local.properties", "android/gradle.properties", CONFIG)
                 if (root / p).is_file())
    return sorted(p for p in names if not set(Path(p).parts) & EXCLUDED
                  and not p.endswith((".apk", ".pyc", ".DS_Store")))


def fingerprint(root):
    digest = hashlib.sha256()
    for name in source_files(root):
        path = root / name
        value = sha256(path) if path.is_file() else "missing"
        digest.update(os.fsencode(name) + b"\0" + value.encode() + b"\0")
    return digest.hexdigest()


def is_android(root):
    return (root / CONFIG).is_file() or any(
        p.endswith("AndroidManifest.xml") for p in source_files(root))


def config_at(root):
    config = read_json(root / CONFIG, {})
    config.setdefault("tasks", ["assembleDebug", "assembleRelease"])
    config.setdefault("gradle_args", [])
    config.setdefault("required_variants", {})
    return config


def apk_record(root, path, module, variant):
    path = path.resolve()
    path.relative_to(root)
    if path.suffix != ".apk" or not path.is_file():
        raise ValueError(f"Missing APK: {path}")
    with zipfile.ZipFile(path) as archive:
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError(f"Not an Android APK: {path}")
    return {"path": str(path.relative_to(root)), "module": module,
            "variant": variant, "size": path.stat().st_size, "sha256": sha256(path)}


def collect_apks(root, config, started):
    artifacts = []
    variants = {}
    for parent, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in EXCLUDED - {"build"}]
        path = Path(parent)
        if "output-metadata.json" not in files or "/build/outputs/apk/" not in str(path) + "/":
            continue
        metadata_path = path / "output-metadata.json"
        metadata = read_json(metadata_path)
        variant = metadata.get("variantName", "")
        if not variant.endswith(("Debug", "Release", "debug", "release")):
            continue
        if metadata_path.stat().st_mtime < started - 1:
            raise ValueError(f"Stale APK metadata: {metadata_path}")
        module = str(path.relative_to(root)).split("/build/outputs/apk")[0]
        elements = metadata.get("elements", [])
        if not elements:
            raise ValueError(f"No APKs for {module}:{variant}")
        variants.setdefault(module, set()).add(variant)
        for element in elements:
            apk = path / element["outputFile"]
            if apk.exists() and apk.stat().st_mtime < started - 1:
                raise ValueError(f"Stale APK: {apk}")
            artifacts.append(apk_record(root, apk, module, variant))
    check_variants(variants, config)
    return sorted(artifacts, key=lambda item: item["path"])


def check_variants(variants, config):
    if not variants:
        raise ValueError("No debug or release APKs were produced")
    for module, names in variants.items():
        debug = {re.sub(r"[Dd]ebug$", "", n) for n in names if n.lower().endswith("debug")}
        release = {re.sub(r"[Rr]elease$", "", n) for n in names if n.lower().endswith("release")}
        if not debug or debug != release:
            raise ValueError(f"Debug/release variants do not match for {module}: {sorted(names)}")
    for module, required in config["required_variants"].items():
        missing = set(required) - variants.get(module, set())
        if missing:
            raise ValueError(f"Missing required APK variants for {module}: {sorted(missing)}")


def verify(root, required_after=0):
    receipt = read_json(root / STATE_DIR / "receipt.json", {})
    if receipt.get("status") != "success":
        raise ValueError("No successful debug AND release build receipt")
    if receipt.get("project") != str(root):
        raise ValueError("Build receipt belongs to another checkout")
    if receipt.get("started", 0) < required_after:
        raise ValueError("The build predates the rebuild request")
    if receipt.get("source_sha256") != fingerprint(root):
        raise ValueError("Sources changed after the recorded build; rebuild both APK types")
    variants = {}
    for item in receipt.get("apks", []):
        path = root / item["path"]
        if not path.is_file() or path.suffix != ".apk":
            raise ValueError(f"Missing APK: {path}")
        if path.stat().st_size != item["size"] or sha256(path) != item["sha256"]:
            raise ValueError(f"APK changed after the build: {path}")
        variants.setdefault(item["module"], set()).add(item["variant"])
    check_variants(variants, config_at(root))
    return receipt


def build(root, extra_args):
    state = root / STATE_DIR
    state.mkdir(parents=True, exist_ok=True)
    with (state / "build.lock").open("w") as lock:
        # Two Codex tasks must not clean/rebuild the same checkout concurrently.
        fcntl.flock(lock, fcntl.LOCK_EX)
        config = config_at(root)
        started = time.time()
        log = state / (time.strftime("build-%Y%m%d-%H%M%S") + ".log")
        receipt = {"status": "running", "project": str(root), "started": started,
                   "source_sha256": fingerprint(root), "log": str(log),
                   "tasks": ["clean", *config["tasks"]],
                   "rerun_tasks": True, "build_cache": False}
        receipt_path = state / "receipt.json"
        write_json(receipt_path, receipt)
        gradle_root = gradle_directory(root)
        command = [str(gradle_root / "gradlew"), "clean", *config["tasks"],
                   "--continue", "--rerun-tasks", "--no-build-cache", "--console=plain",
                   *config["gradle_args"]]
        for key, value in config.get("gradle_properties_files", {}).items():
            command.append(f"-P{key}={Path(value).expanduser()}")
        command.extend(extra_args)
        print(f"Rebuilding debug and release APKs. Log: {log}", flush=True)
        try:
            # Keep Gradle output in a durable log; credentials are never read here.
            with log.open("w") as stream:
                result = subprocess.run(command, cwd=gradle_root, stdout=stream, stderr=subprocess.STDOUT)
            receipt["gradle_exit_code"] = result.returncode
            if result.returncode:
                raise ValueError(f"Gradle exited with code {result.returncode}; inspect {log}")
            receipt["apks"] = collect_apks(root, config, started)
            if fingerprint(root) != receipt["source_sha256"]:
                raise ValueError("Sources changed during the build; rebuild after edits finish")
            receipt.update(status="success", finished=time.time())
            write_json(receipt_path, receipt)
            verify(root)
        except (OSError, ValueError, zipfile.BadZipFile, KeyboardInterrupt) as error:
            receipt.update(status="failed", finished=time.time(), error=str(error))
            write_json(receipt_path, receipt)
            with log.open("a") as stream:
                stream.write(f"\nAndroid build blocked: {error}\n")
            print(f"Android build blocked: {error}", file=sys.stderr)
            return 1
        print_receipt(root, receipt)
        return 0


def print_receipt(root, receipt):
    print(f"Verified {len(receipt['apks'])} APKs against the final source fingerprint.")
    for item in receipt["apks"]:
        print(f"{item['variant']}: {root / item['path']}")
    print(f"Receipt: {root / STATE_DIR / 'receipt.json'}")


def requests_rebuild(prompt):
    return bool(re.search(r"\b(?:rebuild|assemble)\b|\bbuild\b.{0,80}\b(?:app|apk|debug|release)\b",
                          prompt, flags=re.I | re.S))


def hook(payload, state_home=None):
    root = project_at(payload.get("cwd", os.getcwd()))
    if root is None or not is_android(root):
        return {}
    event = payload.get("hook_event_name")
    if event not in ("UserPromptSubmit", "Stop"):
        return {}
    state_home = state_home or Path.home() / ".codex/android-build-state"
    identity = str(root) + "\0" + payload.get("session_id", "default")
    state_path = state_home / (hashlib.sha256(identity.encode()).hexdigest() + ".json")
    current = fingerprint(root)
    state = read_json(state_path, {"baseline": current, "required_after": 0,
                                   "observed_at": time.time()})
    if event == "Stop" and state.get("suppressed"):
        return {}
    if state["baseline"] != current and not state["required_after"]:
        state["required_after"] = state["observed_at"]
    command = f"python3 {shlex.quote(str(Path(__file__).resolve()))} build --project {shlex.quote(str(root))}"
    if event == "UserPromptSubmit":
        prompt = payload.get("prompt", "")
        cancelled = re.fullmatch(
            r"\s*(?:stop|cancel|abort|pause)(?:\s+(?:now|here|this(?: task)?|the task|working|work|the rebuild|the build))?\s*[.!]?\s*",
            prompt, flags=re.I)
        skip_build = re.search(r"\b(?:don't|don’t|do not)\s+(?:rebuild|build|continue)\b", prompt, flags=re.I)
        if cancelled or skip_build:
            write_json(state_path, {"baseline": current, "required_after": 0,
                                   "observed_at": time.time(), "suppressed": True})
            return {}
        state.pop("suppressed", None)
        if requests_rebuild(prompt) and not prompt.startswith(CONTINUATION):
            state["required_after"] = time.time()
        write_json(state_path, state)
        return {"hookSpecificOutput": {"hookEventName": event, "additionalContext":
                "Android completion requirement: after final changes or any rebuild request, run "
                + command + ". It performs clean debug AND release builds, then checks source and APK "
                "hashes. Configure scripts/android-build.json if project-specific variants/signing "
                "are needed. Never count an earlier or still-running build as completion. "
                "If the build fails, fix it when possible; otherwise report 'Android build blocked:' "
                "with the actual blocker and absolute build log path."}}
    if not state["required_after"]:
        return {}
    try:
        verify(root, state["required_after"])
    except (OSError, ValueError, KeyError) as error:
        receipt = read_json(root / STATE_DIR / "receipt.json", {})
        message = payload.get("last_assistant_message") or ""
        # A real failed attempt can finish only with an explicit, inspectable blocker.
        if (receipt.get("status") == "failed"
                and receipt.get("source_sha256") == current
                and receipt.get("started", 0) >= state["required_after"]
                and "Android build blocked:" in message
                and receipt.get("log") and receipt["log"] in message
                and Path(receipt["log"]).is_file()):
            return {}
        write_json(state_path, state)
        return {"decision": "block", "reason": f"{CONTINUATION} {error}. Run {command} "
                "after all edits finish, then verify and provide existing debug and release APK paths. "
                "If a real build attempt fails and cannot be fixed, explicitly report "
                "'Android build blocked:' and include its absolute log path."}
    write_json(state_path, {"baseline": current, "required_after": 0, "observed_at": time.time()})
    return {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "verify", "hook"))
    parser.add_argument("--project", type=Path, default=Path.cwd())
    args, extra = parser.parse_known_args()
    if args.action == "hook":
        try:
            print(json.dumps(hook(json.load(sys.stdin))))
            return 0
        except (OSError, ValueError, KeyError) as error:
            # Surface broken enforcement; never silently accept an invalid receipt.
            print(f"Android APK completion check failed: {error}", file=sys.stderr)
            return 2
    root = project_at(args.project)
    if root is None or not is_android(root):
        parser.error("No Android Gradle project found")
    if args.action == "build":
        return build(root, extra[1:] if extra[:1] == ["--"] else extra)
    try:
        print_receipt(root, verify(root))
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f"Android APK verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

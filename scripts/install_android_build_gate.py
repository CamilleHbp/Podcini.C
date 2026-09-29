#!/usr/bin/env python3
"""Install the shared APK check without changing Codex hook trust decisions."""
import argparse
import json
from pathlib import Path
import shlex
import shutil
import time

from android_build_gate import write_json


def install(codex_home, personal_root):
    script = codex_home / "scripts/android_build_gate.py"
    hooks_path = codex_home / "hooks.json"
    hooks = json.loads(hooks_path.read_text()) if hooks_path.exists() else {"hooks": {}}
    command = "python3 " + shlex.quote(str(script)) + " hook"
    for event in ("UserPromptSubmit", "Stop"):
        groups = hooks["hooks"].setdefault(event, [])
        if not any(handler.get("command") == command
                   for group in groups for handler in group.get("hooks", [])):
            groups.append({"hooks": [{"type": "command", "command": command,
                                      "timeout": 60, "statusMessage": "Checking current Android APKs"}]})
    script.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name("android_build_gate.py"), script)
    if hooks_path.exists() and json.loads(hooks_path.read_text()) != hooks:
        shutil.copyfile(hooks_path, hooks_path.with_name("hooks.json.before-android-build-" + time.strftime("%Y%m%d-%H%M%S")))
    write_json(hooks_path, hooks)
    instruction = (
        "\n## Android build completion\n\n"
        "For every Android app, including Groceries, Podcini.C, and VerveTwoDo, "
        "after the final changes and whenever the user requests a rebuild, run "
        f"`python3 {script} build --project <project-root>` (or the project's "
        "`sh scripts/rebuild-apks` wrapper), wait for completion, then run the same "
        "script with `verify --project <project-root>`. Both debug and release APKs "
        "must actually rebuild for all application variants. The runner uses clean "
        "builds with task reruns and no build cache, and verifies source fingerprints "
        "and APK hashes. If project-specific variants or signing paths are needed, "
        "configure `scripts/android-build.json` using the project's existing setup; "
        "never put signing secrets in instructions. An earlier build, a background "
        "build still running, or existing APK exports is not completion evidence. "
        "Any subsequent source changes, including changes by another task, require "
        "another build. Link only to existing verified debug and release APKs. "
        "Fix build failures when possible; otherwise explicitly report "
        "`Android build blocked:` with the actual error and absolute attempt-log path. "
        "This requirement supersedes any memory or guidance recommending debug-only "
        "validation. Keep the shared runner synchronized when changing the project's "
        "build-check script. New Codex hooks must be reviewed and trusted through "
        "`/hooks`; do not edit trust hashes or bypass hook trust.\n"
    )
    for path in (codex_home / "AGENTS.md", personal_root / "AGENTS.md"):
        existing = path.read_text() if path.exists() else ""
        marker = "\n## Android build completion\n"
        if marker not in existing:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(existing.rstrip() + "\n" + instruction)
    print(f"Installed shared runner: {script}")
    print(f"Preserved existing hooks and added UserPromptSubmit/Stop checks: {hooks_path}")
    print("Activation requires one-time review and trust of the two new hooks in Codex /hooks.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex-home", type=Path, default=Path.home() / ".codex")
    parser.add_argument("--personal-root", type=Path, default=Path.home() / "Dev/Personal")
    args = parser.parse_args()
    install(args.codex_home.resolve(), args.personal_root.resolve())

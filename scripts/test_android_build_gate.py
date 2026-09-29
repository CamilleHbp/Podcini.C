"""Regression tests for stale/missing APKs and premature task completion."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

import android_build_gate as gate
import install_android_build_gate as installer


class BuildGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / "android"
        self.root.mkdir()
        (self.root / "gradlew").write_text("#!/bin/sh\n")
        self.source = self.root / "app/src/main/AndroidManifest.xml"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("<manifest />")
        self.hook_home = Path(self.temp.name) / "hooks"
        self.event = {"cwd": str(self.root), "session_id": "test", "hook_event_name": "UserPromptSubmit"}
        self.started = time.time() - 0.1

    def artifacts(self, variants=("freeDebug", "freeRelease")):
        for variant in variants:
            folder = self.root / "app/build/outputs/apk" / variant
            folder.mkdir(parents=True, exist_ok=True)
            apk = folder / (variant + ".apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", "manifest")
            gate.write_json(folder / "output-metadata.json", {
                "variantName": variant, "elements": [{"outputFile": apk.name}]})

    def success(self):
        self.artifacts()
        receipt = {"status": "success", "project": str(self.root), "started": time.time(),
                   "finished": time.time(), "source_sha256": gate.fingerprint(self.root),
                   "apks": gate.collect_apks(self.root, gate.config_at(self.root), self.started)}
        gate.write_json(self.root / gate.STATE_DIR / "receipt.json", receipt)
        return receipt

    def submit(self, prompt="Update the settings screen"):
        return gate.hook(dict(self.event, prompt=prompt), self.hook_home)

    def stop(self, message="Done."):
        return gate.hook(dict(self.event, hook_event_name="Stop", last_assistant_message=message), self.hook_home)

    def test_current_debug_and_release_pass(self):
        self.success()
        self.assertEqual(len(gate.verify(self.root)["apks"]), 2)

    def test_nested_android_project_detected_from_web_root_and_native_subdirectory(self):
        gate.subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        native = self.root / "android"
        native.mkdir()
        (self.root / "gradlew").rename(native / "gradlew")
        self.assertEqual(gate.project_at(self.root), self.root)
        self.assertEqual(gate.project_at(native), self.root)
        self.assertEqual(gate.gradle_directory(self.root), native)
        before = gate.fingerprint(self.root)
        (self.root / "web").mkdir()
        (self.root / "web/index.html").write_text("updated web UI")
        self.assertNotEqual(gate.fingerprint(self.root), before)

    def test_nested_project_invokes_its_own_gradle_directory(self):
        native = self.root / "android"
        native.mkdir()
        (self.root / "gradlew").rename(native / "gradlew")
        real_run = gate.subprocess.run

        def run(command, **kwargs):
            if command[0].endswith("gradlew"):
                self.assertEqual(command[0], str(native / "gradlew"))
                self.assertEqual(kwargs["cwd"], native)
                return gate.subprocess.CompletedProcess(command, 1)
            return real_run(command, **kwargs)

        with patch.object(gate.subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.build(self.root, []), 1)

    def test_source_edit_invalidates_build(self):
        self.success()
        self.source.write_text("changed")
        with self.assertRaisesRegex(ValueError, "Sources changed"):
            gate.verify(self.root)

    def test_new_and_deleted_sources_invalidate_build(self):
        for action in ("add", "delete"):
            with self.subTest(action=action):
                self.success()
                if action == "add":
                    (self.source.parent / "new.xml").write_text("new")
                else:
                    self.source.unlink()
                with self.assertRaisesRegex(ValueError, "Sources changed"):
                    gate.verify(self.root)

    def test_missing_or_renamed_release_rejected(self):
        receipt = self.success()
        apk = self.root / receipt["apks"][1]["path"]
        apk.rename(apk.with_suffix(""))
        with self.assertRaisesRegex(ValueError, "Missing APK"):
            gate.verify(self.root)

    def test_modified_apk_rejected(self):
        receipt = self.success()
        (self.root / receipt["apks"][1]["path"]).write_bytes(b"wrong")
        with self.assertRaisesRegex(ValueError, "APK changed"):
            gate.verify(self.root)

    def test_debug_alone_rejected(self):
        self.artifacts(("freeDebug",))
        with self.assertRaisesRegex(ValueError, "Debug/release"):
            gate.collect_apks(self.root, gate.config_at(self.root), self.started)

    def test_required_flavor_must_exist(self):
        self.artifacts()
        config = gate.config_at(self.root)
        config["required_variants"] = {"app": ["playDebug", "playRelease"]}
        with self.assertRaisesRegex(ValueError, "Missing required APK variants"):
            gate.collect_apks(self.root, config, self.started)

    def test_earlier_build_does_not_satisfy_rebuild_request(self):
        self.success()
        self.submit("Rebuild the app")
        self.assertEqual(self.stop()["decision"], "block")
        self.success()
        self.assertEqual(self.stop(), {})

    def test_read_only_turn_does_not_require_build(self):
        self.submit("Explain how settings work")
        self.assertEqual(self.stop(), {})

    def test_explicit_stop_or_skip_build_is_respected(self):
        for prompt in ("Stop", "Cancel the task", "Don't rebuild this time", "Do not build yet"):
            with self.subTest(prompt=prompt):
                self.submit("Rebuild the app")
                self.source.write_text(prompt)
                self.submit(prompt)
                self.assertEqual(self.stop(), {})
                # A subsequent explicit rebuild request reinstates the requirement.
                self.submit("Rebuild the app")
                self.assertEqual(self.stop()["decision"], "block")

    def test_edit_blocks_stop_until_both_apks_current(self):
        self.submit()
        self.source.write_text("changed")
        self.assertEqual(self.stop()["decision"], "block")
        self.success()
        self.assertEqual(self.stop(), {})

    def test_continuation_does_not_reset_build_requirement(self):
        self.submit()
        self.source.write_text("changed")
        reason = self.stop()["reason"]
        self.success()
        self.submit(reason)
        self.assertEqual(self.stop(), {})

    def test_failed_attempt_requires_explicit_blocker_and_log(self):
        self.submit("Rebuild the app")
        log = str(self.root / gate.STATE_DIR / "failure.log")
        Path(log).parent.mkdir(parents=True, exist_ok=True)
        Path(log).write_text("signing key unavailable")
        gate.write_json(self.root / gate.STATE_DIR / "receipt.json", {
            "status": "failed", "source_sha256": gate.fingerprint(self.root),
            "started": time.time(), "log": log})
        self.assertEqual(self.stop("Build complete")["decision"], "block")
        self.assertEqual(self.stop("Android build blocked: signing key unavailable. " + log), {})

    def test_clean_rerun_no_cache_and_failed_gradle_invalidates_old_receipt(self):
        self.success()
        real_run = gate.subprocess.run

        def run(command, **kwargs):
            if command[0].endswith("gradlew"):
                for option in ("clean", "assembleDebug", "assembleRelease", "--rerun-tasks", "--no-build-cache"):
                    self.assertIn(option, command)
                return gate.subprocess.CompletedProcess(command, 1)
            return real_run(command, **kwargs)

        with patch.object(gate.subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.build(self.root, []), 1)
        with self.assertRaisesRegex(ValueError, "No successful"):
            gate.verify(self.root)

    def test_changes_during_build_reject_success(self):
        real_run = gate.subprocess.run

        def run(command, **kwargs):
            if command[0].endswith("gradlew"):
                self.artifacts()
                self.source.write_text("concurrent edit")
                return gate.subprocess.CompletedProcess(command, 0)
            return real_run(command, **kwargs)

        with patch.object(gate.subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.build(self.root, []), 1)
        receipt = gate.read_json(self.root / gate.STATE_DIR / "receipt.json")
        self.assertEqual(receipt["status"], "failed")
        self.assertIn("during the build", receipt["error"])

    def test_stale_artifacts_rejected_even_if_gradle_exits_zero(self):
        self.artifacts()
        with self.assertRaisesRegex(ValueError, "Stale APK"):
            gate.collect_apks(self.root, gate.config_at(self.root), time.time() + 10)

    def test_generated_outputs_do_not_change_source_fingerprint(self):
        before = gate.fingerprint(self.root)
        self.artifacts()
        gate.write_json(self.root / gate.STATE_DIR / "receipt.json", {"status": "running"})
        self.assertEqual(gate.fingerprint(self.root), before)

    def test_git_sources_include_untracked_and_ignored_local_properties(self):
        gate.subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        (self.root / ".gitignore").write_text(".gradle/\nbuild/\nlocal.properties\n")
        gate.subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        before = gate.fingerprint(self.root)
        (self.root / "local.properties").write_text("sdk.dir=/some/sdk")
        self.assertNotEqual(gate.fingerprint(self.root), before)
        before = gate.fingerprint(self.root)
        (self.source.parent / "new.xml").write_text("new untracked source")
        self.assertNotEqual(gate.fingerprint(self.root), before)
        before = gate.fingerprint(self.root)
        self.source.unlink()
        self.assertNotEqual(gate.fingerprint(self.root), before)

    def test_install_is_idempotent_preserves_hooks_and_does_not_touch_trust(self):
        codex = Path(self.temp.name) / "codex"
        personal = Path(self.temp.name) / "personal"
        original = {"type": "command", "command": "existing-check"}
        gate.write_json(codex / "hooks.json", {"hooks": {"Stop": [{"hooks": [original]}]}})
        (codex / "config.toml").write_text("# Existing trust decisions\n")
        with contextlib.redirect_stdout(io.StringIO()):
            installer.install(codex, personal)
            installer.install(codex, personal)
        hooks = gate.read_json(codex / "hooks.json")["hooks"]
        self.assertEqual(hooks["Stop"][0]["hooks"][0], original)
        self.assertEqual(len(hooks["Stop"]), 2)
        self.assertEqual(len(hooks["UserPromptSubmit"]), 1)
        self.assertEqual((codex / "config.toml").read_text(), "# Existing trust decisions\n")
        self.assertEqual((personal / "AGENTS.md").read_text().count("## Android build completion"), 1)


if __name__ == "__main__":
    unittest.main()

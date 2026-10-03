# Android APK completion check

After the last edit, run:

```sh
sh scripts/rebuild-apks
python3 scripts/android_build_gate.py verify
```

The rebuild command runs `clean`, both aggregate assemble tasks, `--rerun-tasks`,
and `--no-build-cache`. It covers free and play in both
debug and release, including their ABI splits. Signing properties stay in
`~/.android/podcini-c/release.properties`.

The command records a successful receipt only when Gradle succeeds, all required
variants exist, and sources remain unchanged during the build. Verification
checks the current source fingerprint and every APK's path, size, and SHA-256.
New, edited, deleted sources and missing, renamed, or modified APKs invalidate
the receipt. Failed attempts replace the previous success receipt.

Logs and the latest receipt live under `.gradle/codex-apk/`. Final answers must
link to existing APK paths printed by verification. A build started before the
last changes, a background build still running, or old exported files cannot
serve as completion evidence. If another task changes the checkout while the
build runs, wait for its edits to finish and rebuild again.

## Shared Codex hook

Install or update the shared check and persistent instructions with
`python3 scripts/install_android_build_gate.py`. This preserves existing hooks
and does not change their trust decisions.

The installed copy at `~/.codex/scripts/android_build_gate.py` works across
Android repositories. Optional `scripts/android-build.json` configures tasks,
required variants, extra Gradle arguments, and paths to signing property files.
The default tasks are `assembleDebug` and `assembleRelease`.
Root-level Gradle projects and an `android/` subproject are detected from either
the repository root or a native source subdirectory. For nested projects such
as Groceries, the source fingerprint also covers the surrounding web sources.

`UserPromptSubmit` records the source state and rebuild requests. `Stop` asks
Codex to continue when edits or a rebuild request lack verified current APKs.
A genuine failed build can be reported only with `Android build blocked:` and
the full path to that attempt's log. A read-only task with unchanged sources
does not require a build. Explicit stop/pause requests and instructions not to
build are respected. Interrupting the agent remains available normally.

New hooks require one-time review and trust through Codex `/hooks` before they
run. Do not modify trust hashes or bypass hook trust. Existing hook definitions
are preserved when installing this check. Hook configuration is documented at
<https://learn.chatgpt.com/docs/hooks>.

Run the regression checks with:

```sh
python3 -m unittest discover -s scripts -p 'test_android_build_gate.py'
```

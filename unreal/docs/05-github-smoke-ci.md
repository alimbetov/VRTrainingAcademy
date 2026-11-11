# GitHub smoke qualification — exact claims and setup

## What passes on an ordinary GitHub runner

The `contract-smoke` job in `.github/workflows/quest-runtime-smoke.yml`
checks the `unreal/tests/` Python suite on Ubuntu. It validates the
`.uproject` / source structure, JSON schema fixture and default-deny
downloaded-level entry point. **It does not compile Unreal Engine C++**.

The existing `vr-content-delivery.yml` job independently exercises
Java 21, PostgreSQL 16 and the Python contracts. Backend CI success is not a
Quest Runtime acceptance certificate.

## Compile native C++ on GitHub Actions

Unreal Engine 5.6 must be installed and licensed for the build machine.
GitHub-hosted Ubuntu runners do **not** come with the UE editor/toolchain.

1. Configure a **trusted private self-hosted macOS ARM64 runner**; add
   custom label `unreal-5-6` alongside `self-hosted,macOS,ARM64`.
2. Install Unreal Engine 5.6, Xcode and the platform tools on the runner.
3. Define `UE_ROOT` in the runner service environment, pointing at the UE
   root directory containing `Engine/Build/BatchFiles/Mac/Build.sh`.
4. Set repository Actions variable `VRTA_UNREAL_NATIVE_CI=true`.
   Without it, `native-compile-smoke` is SKIPPED, not PASSED.
5. Push a change under `unreal/` or use the workflow after it is available
   on the repository default branch.
6. Review the *native-compile-smoke* job independently; confirm the
   `Build.sh VRTrainingQuestEditor Mac Development` command was executed.

**Security:** do not register an unprotected personal machine as a runner
for untrusted external PRs. Limit access/branch permissions and keep tokens
and publishing secrets outside untrusted workflow contexts.

## Further tests required before a real lesson-smoke PASS

- Package `VRTrainingQuest` for **Android ASTC** via UAT (separate
  configuration, verified Android SDK/NDK and signing).
- Provide real cooked level `/Game/Lessons/Smoke/L_OfflineSmoke`.
- Build and sign a separate Android-cooked chunk with a verified manifest;
  test mount and `OpenLevel` on the actual Quest runtime version.
- Install the APK on Meta Quest via ADB and capture logs/screenshots.
- Turn off Wi-Fi, open level, force-stop, restart the headset, reopen.
- Test tampered artifacts, expired offline license, failed partial downloads,
  interrupted installs and no data loss; attach evidence to the PR.

Because current C++ intentionally returns false from
`OpenDownloadedLesson`, **downloaded lesson mount/play should currently
fail**. A green native compilation test means the code can be compiled,
not that the gameplay journey is complete.

## Branch protection and required checks

Require `Contract smoke (Python, no Unreal compilation)`, the Backend
PostgreSQL job and peer review. Once the self-hosted runner is provisioned,
require a **non-skipped** `Native Unreal C++ smoke` in a dedicated native
release protection policy. A skipped job must never be presented as native
success. Keep APK/device qualification as a separately evidenced acceptance
gate; it cannot be certified by hosted Python tests.

## Added qualification split (R0.2)

The native macOS script now checks the actual installed UE 5.6
`Engine/Build/Build.version`, runs UBT, and verifies that a non-empty
`VRTrainingQuest` module artifact was generated. It does not silently
pass if UE is missing or only source files exist.

The **Android ASTC APK packaging** job is separately gated. To enable it:

1. First qualify native compile with repository variable
   `VRTA_UNREAL_NATIVE_CI=true`.
2. Install supported Android SDK/NDK and expose
   `ANDROID_HOME`, `ANDROID_NDK_ROOT` on the trusted runner.
3. In UE Editor, save a cookable map at
   `unreal/VRTrainingQuest/Content/Lessons/Smoke/L_OfflineSmoke.umap`.
4. Set `VRTA_UNREAL_ANDROID_CI=true` in GitHub Actions variables.
5. The UAT script builds, cooks, stages, packages and confirms a **nonempty
   .apk**. It deliberately fails when the smoke map, SDK or UAT is missing.

Both native jobs are restricted to **non-pull_request** events on a
trusted self-hosted macOS ARM64 runner. Use protected branch policies,
restrict who may push to branches running the job, and keep runner tokens
out of arbitrary projects. The native compile job can be **SKIPPED** if
the runner variable is false, without affecting the contract job.

**CI interpretation:**
- `Contract smoke: success` = metadata/static-source contracts only.
- `Native Unreal C++: success` = real C++ module compiled.
- `Android ASTC: success` = APK packaged (not installed).
- Real Quest `Mount → Play → Offline Reopen` remains a separately recorded
  hardware acceptance; no CI branch variable or static test can claim it.

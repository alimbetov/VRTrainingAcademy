# Quest Native Runtime Foundation — acceptance and limitations

## Architecture decision

A **single installed VRTrainingQuest APK** owns navigation, OpenXR runtime,
lesson interaction framework and offline storage. Each lesson is a logical
versioned package of Android-cooked resources, not a separate executable APK.

The initial Unreal C++ project and `UVRTAQuestGameInstance` establish only
the native project/module structure, local persistent directory and a
bundled-lesson offline open function. No cooked built-in test map has been
committed; one must be created in Unreal Editor. No actual Quest build or
mount verification has occurred.

**Downloaded content is fail-closed in C++**:
`OpenDownloadedLesson()` intentionally returns false. An authenticated device,
license, signed package manifest, per-artifact SHA-256, compatible engine/runtime
and verified install are mandatory gates before remote content mount/open.

## Format and build decisions to prove

- Pin UE **5.6 as provisional baseline**; validate installed UE version,
  Meta Quest target SDK, Android toolchain and plugin compatibility.
- Start with **Pak chunk / ChunkDownloader** proof. UE also supports
  IoStore (`.utoc/.ucas`): do not assume mounting Pak alone works for
  content cooked as IoStore. Choose one build format and test its native
  mount on actual Quest.
- Content cook must use the **same compatible engine build** and target
  Android ASTC as the installed shell, and stable package paths.
- `entryLevel` under `/Game/Lessons/` only, from a **verified signed**
  manifest; never from a caller-controlled HTTP parameter.
- Persist releases under `FPaths::ProjectPersistentDownloadDir()/VRTA/releases`,
  staged/verified/installed/active states, atomic rename and recovery.
- No lesson may run merely because an unsigned manifest claims it is installed.
- Quest offline license is a separate signed and bounded lease, not file existence.

## A0 RED acceptance matrix

| Test | Evidence | Status |
|---|---|---|
| Source project and plugin metadata | Python `test_quest_native_foundation.py` | CI automatic |
| Strict logical package schema and path rejection | Python fixtures | CI automatic |
| Denial of unverified downloaded content | Static C++ contract test | CI automatic |
| Unreal Editor 5.6 opens .uproject | UBT / AutomationTool build log | NOT RUN |
| Android ASTC package builds and installs | APK, ADB install and launch log | NOT RUN |
| Bundled level opens offline | Native Quest screenshot + UE logs | NOT RUN |
| Distinct signed lesson package cooked | Build artifacts / signed hash evidence | NOT RUN |
| RustFS grant, download and integrity verification | Local Backend+RustFS E2E | NOT RUN |
| Mount downloaded pak/chunk and load level | Instrumented UE runtime logs | NOT RUN |
| Disconnect Wi-Fi; reopen lesson after Quest restart | ADB offline/restart report | NOT RUN |
| Tampered manifest/file and expired offline license rejected | Native negative test | NOT RUN |
| Close/killed app during staging and resume safely | Device crash recovery report | NOT RUN |

## Machine-specific instructions for development agent

1. Open `unreal/VRTrainingQuest/VRTrainingQuest.uproject` in UE 5.6 with
   Meta Quest / Android SDK installed. Report exact engine and SDK versions.
2. Resolve any actual UBT compilation errors (do not conceal failed builds).
3. Create packaged test map `/Game/Lessons/Smoke/L_OfflineSmoke` with a
   world, one interactable actor, and a return to menu control.
4. Compile and package **Android ASTC** for the actual Quest target.
5. Install via ADB; collect app logs and screenshot of the built-in map
   with Wi-Fi disabled.
6. Make a separate DLC/chunk cook compatible with that exact base build.
   Confirm mount mechanism on Android with UE logs. Do **not** bypass
   signature/hash checks to claim a production-ready package installer.
7. Implement signed-release verification, verified staging registry and
   only then wire mount + OpenLevel. Use existing Java R1 reference as
   protocol guidance but do not copy unverified platform assumptions.
8. Save a reproducible `docs/uat/quest-native-runtime-YYYY-MM-DD.md` with
   exact SHA, UE+SDK, device model, Android/Quest version, logs, screenshots,
   results and failures.

The current CI only executes Python fixtures; it **does not build C++**.
Production qualification must independently verify device-specific mount
and offline reopen.

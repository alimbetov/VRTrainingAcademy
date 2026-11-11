"""Static source/fixture checks, NOT a substitute for Unreal build or Quest UAT."""
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROJ = ROOT / "VRTrainingQuest"
PACKAGE = ROOT / "contracts" / "quest-lesson-package.v1.example.json"


def validate_lesson_package(data):
    expected = {"schemaVersion", "releaseId", "lessonId", "collectionId",
                "targetPlatform", "runtimeMinVersion", "engineVersion",
                "entryLevel", "installMode", "artifacts"}
    if not isinstance(data, dict) or set(data) != expected:
        raise ValueError("Invalid manifest fields")
    if data["schemaVersion"] != "vrta.lesson-package.v1":
        raise ValueError("Unsupported schema")
    if data["targetPlatform"] != "Android_ASTC" or data["installMode"] != "pak-chunk":
        raise ValueError("Unsupported platform or installation mode")
    for key in ("releaseId", "lessonId", "collectionId"):
        if not isinstance(data[key], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", data[key]):
            raise ValueError("Unsafe identity")
    if not isinstance(data["engineVersion"], str) or not re.fullmatch(r"\d+\.\d+", data["engineVersion"]):
        raise ValueError("Invalid UE version")
    if not isinstance(data["runtimeMinVersion"], str) or not re.fullmatch(r"\d+\.\d+\.\d+", data["runtimeMinVersion"]):
        raise ValueError("Invalid Runtime version")
    path = data["entryLevel"]
    if not isinstance(path, str) or not path.startswith("/Game/Lessons/") or ".." in path or "\\" in path:
        raise ValueError("Unsafe level path")
    artifacts = data["artifacts"]
    if not isinstance(artifacts, list) or not (1 <= len(artifacts) <= 32):
        raise ValueError("Invalid artifacts list")
    names = set()
    for item in artifacts:
        if not isinstance(item, dict) or set(item) != {"name", "sizeBytes", "sha256"}:
            raise ValueError("Invalid artifact")
        name = item["name"]
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.pak", name):
            raise ValueError("Unsafe artifact filename")
        if name in names:
            raise ValueError("Duplicate artifact filename")
        names.add(name)
        if type(item["sizeBytes"]) is not int or not (0 < item["sizeBytes"] <= 8 * 1024**3):
            raise ValueError("Invalid artifact size")
        if not isinstance(item["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise ValueError("Invalid SHA-256")
    return True


class QuestNativeFoundationTest(unittest.TestCase):
    def test_unreal_module_is_real_source_project(self):
        p = json.loads((PROJ / "VRTrainingQuest.uproject").read_text())
        self.assertTrue(any(m["Name"] == "VRTrainingQuest" for m in p["Modules"]))
        self.assertTrue(any(m["Name"] == "ChunkDownloader" and m["Enabled"] for m in p["Plugins"]))
        self.assertTrue((PROJ / "Source" / "VRTrainingQuest.Target.cs").is_file())
        self.assertTrue((PROJ / "Source" / "VRTrainingQuestEditor.Target.cs").is_file())
        self.assertTrue((PROJ / "Source" / "VRTrainingQuest" / "VRTrainingQuest.Build.cs").is_file())

    def test_example_manifest_is_well_formed(self):
        self.assertTrue(validate_lesson_package(json.loads(PACKAGE.read_text())))

    def test_manifest_rejects_path_traversal_and_wrong_platform(self):
        d = json.loads(PACKAGE.read_text())
        for mutation in (
            {"entryLevel": "/Game/Lessons/../Admin"},
            {"targetPlatform": "Windows"},
            {"installMode": "execute-apk"},
            {"schemaVersion": "vrta.lesson-package.v999"},
            {"entryLevel": "/Other/Level"},
        ):
            with self.subTest(mutation=mutation):
                self.assertRaises(ValueError, validate_lesson_package, {**d, **mutation})

    def test_manifest_rejects_duplicate_and_malformed_artifacts(self):
        d = json.loads(PACKAGE.read_text())
        art = d["artifacts"][0]
        for bad in ([art, art], [{**art, "sha256": "INVALID"}],
                    [{**art, "name": "../evil.pak"}], [{**art, "sizeBytes": 0}]):
            with self.subTest(bad=bad):
                self.assertRaises(ValueError, validate_lesson_package, {**d, "artifacts": bad})

    def test_downloaded_content_is_fail_closed(self):
        cpp = (PROJ / "Source/VRTrainingQuest/Private/VRTAQuestGameInstance.cpp").read_text()
        fn = cpp.split("bool UVRTAQuestGameInstance::OpenDownloadedLesson(", 1)[1].split(
            "FString UVRTAQuestGameInstance::GetLocalLessonDirectory()", 1)[0]
        self.assertIn("return false;", fn)
        self.assertNotIn("OpenLevel(", fn)
        self.assertIn("ProjectPersistentDownloadDir", cpp)
        self.assertIn("OpenBundledLessonOffline", cpp)


if __name__ == "__main__":
    unittest.main()

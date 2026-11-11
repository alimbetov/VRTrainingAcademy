"""Cross-language signing protocol vector. NOT native Quest signature verification."""
import hashlib
import json
from pathlib import Path
import re
import struct
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "contracts" / "quest-lesson-package.v1.example.json"
MAGIC = b"VRTA-QUEST-RELEASE-V2\n"
EXPECTED_SHA256 = "18ff9138abf0831c7ffd2a33f0281b1cea2eb195e7b4469c443ad718332ae290"


def canonical_v2(descriptor):
    assert descriptor["schemaVersion"] == "vrta.lesson-package.v1"
    assert descriptor["targetPlatform"] == "Android_ASTC"
    assert descriptor["engineVersion"] == "5.6"
    assert descriptor["installMode"] == "pak-chunk"
    assert re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", descriptor["runtimeBuildId"])
    assert re.fullmatch(r"/Game/Lessons/(?:[A-Za-z][A-Za-z0-9_]*/)*[A-Za-z][A-Za-z0-9_]*",
                        descriptor["entryLevel"])
    out = bytearray(MAGIC)
    for key in ("releaseId", "lessonId", "collectionId", "targetPlatform",
                "engineVersion", "runtimeBuildId", "entryLevel"):
        data = descriptor[key].encode("ascii")
        out += struct.pack(">I", len(data)) + data
    artifacts = descriptor["artifacts"]
    assert 1 <= len(artifacts) <= 32
    assert len({a["name"] for a in artifacts}) == len(artifacts)
    out += struct.pack(">I", len(artifacts))
    for artifact in sorted(artifacts, key=lambda a: a["name"]):
        name = artifact["name"].encode("ascii")
        assert re.fullmatch(rb"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\.pak", name)
        assert 0 < artifact["sizeBytes"] <= 512 * 1024 * 1024
        out += struct.pack(">I", len(name)) + name
        out += struct.pack(">Q", artifact["sizeBytes"])
        digest = bytes.fromhex(artifact["sha256"])
        assert len(digest) == 32
        out += digest
    return bytes(out)


class SignedQuestReleaseV2CrossContractTest(unittest.TestCase):
    def test_independent_python_encoder_matches_java_signature_vector(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        encoded = canonical_v2(data)
        self.assertEqual(237, len(encoded))
        self.assertEqual(EXPECTED_SHA256, hashlib.sha256(encoded).hexdigest())

    def test_backend_upload_metadata_matches_unreal_release_fixture(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        upload = json.loads((ROOT / "contracts" / "quest-lesson-upload.v1.example.json")
                            .read_text(encoding="utf-8"))
        for left, right in (
            ("releaseId", "releaseId"), ("lessonId", "lessonId"),
            ("collectionId", "collectionId"), ("platform", "targetPlatform"),
            ("engineVersion", "engineVersion"),
            ("runtimeBuildId", "runtimeBuildId"),
            ("entryLevel", "entryLevel"),
        ):
            self.assertEqual(upload[left], data[right], f"Upload/release drift: {left}")
        artifact = data["artifacts"][0]
        self.assertEqual(upload["artifactName"], artifact["name"])
        self.assertEqual(upload["artifactSizeBytes"], artifact["sizeBytes"])
        self.assertEqual(upload["sha256Hex"], artifact["sha256"])

    def test_unreal_runtime_version_and_engine_match_authoring_descriptor(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        cpp = (ROOT / "VRTrainingQuest" / "Source" / "VRTrainingQuest" /
               "Private" / "VRTAQuestGameInstance.cpp").read_text(encoding="utf-8")
        project = json.loads((ROOT / "VRTrainingQuest" / "VRTrainingQuest.uproject")
                             .read_text(encoding="utf-8"))
        self.assertIn('return TEXT("' + data["runtimeBuildId"] + '");', cpp)
        self.assertEqual(data["engineVersion"], project["EngineAssociation"])

    def test_changing_level_runtime_or_hash_changes_canonical_digest(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        original = hashlib.sha256(canonical_v2(data)).digest()
        for key, value in (("entryLevel", "/Game/Lessons/Physics/Maps/L_Forged"),
                           ("runtimeBuildId", "0.1.1-unknown")):
            changed = {**data, key: value}
            with self.subTest(key=key):
                self.assertNotEqual(original, hashlib.sha256(canonical_v2(changed)).digest())
        changed = json.loads(FIXTURE.read_text(encoding="utf-8"))
        changed["artifacts"][0]["sha256"] = "b" * 64
        self.assertNotEqual(original, hashlib.sha256(canonical_v2(changed)).digest())


if __name__ == "__main__":
    unittest.main()

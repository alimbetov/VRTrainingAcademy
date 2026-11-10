#!/usr/bin/env python3
"""Pure-data contract tests, no Unreal installation required."""
import copy
import json
import unittest
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[1] / "contracts" / "catalog.snapshot.v1.example.json"

def validate(c):
    assert c["schemaVersion"] == 1
    refs = {}
    for coll in ("worlds", "areas", "subjects", "lessons", "experiences"):
        ids = [x["id"] for x in c[coll]]
        assert len(ids) == len(set(ids)), "duplicate id in " + coll
        refs[coll] = set(ids)
        assert all(x["id"] for x in c[coll])
    world_by_id = {w["id"]: w for w in c["worlds"]}
    area_by_id = {a["id"]: a for a in c["areas"]}
    subject_by_id = {s["id"]: s for s in c["subjects"]}
    for a in c["areas"]:
        assert a["worldId"] in refs["worlds"], "dangling world"
    for s in c["subjects"]:
        assert s["areaId"] in refs["areas"], "dangling area"
    for l in c["lessons"]:
        assert l["experienceId"] in refs["experiences"], "dangling experience"
    placements = set()
    for p in c["placements"]:
        key = tuple(p[k] for k in ("worldId", "areaId", "subjectId", "lessonId"))
        assert key not in placements, "duplicate placement"
        placements.add(key)
        assert p["worldId"] in world_by_id
        assert p["areaId"] in area_by_id
        assert p["subjectId"] in subject_by_id
        assert p["lessonId"] in refs["lessons"]
        assert area_by_id[p["areaId"]]["worldId"] == p["worldId"], "area/world mismatch"
        assert subject_by_id[p["subjectId"]]["areaId"] == p["areaId"], "subject/area mismatch"
    keys = {x["titleKey"] for group in ("worlds", "areas", "subjects", "lessons") for x in c[group]}
    assert keys <= set(c["localizations"][c["defaultLocale"]]), "missing default locale keys"
    assert all(set(v) <= keys for v in c["localizations"].values()), "unknown translation key"

def localized(c, locale, key):
    return c["localizations"].get(locale, {}).get(key) or c["localizations"][c["defaultLocale"]][key]

class ContractTest(unittest.TestCase):
    def setUp(self):
        self.c = json.loads(CATALOG.read_text(encoding="utf-8"))

    def test_example_is_consistent(self):
        validate(self.c)
        self.assertEqual(len(self.c["lessons"]), 3)

    def test_shared_lesson_only_one_id(self):
        validate(self.c)
        self.assertEqual(sum(p["lessonId"] == "SAFE-001" for p in self.c["placements"]), 2)

    def test_missing_locale_falls_back(self):
        self.assertEqual(localized(self.c, "de-DE", "lesson.astro"), "Solar System")

    def test_duplicate_placement_rejected(self):
        c = copy.deepcopy(self.c)
        c["placements"].append(copy.deepcopy(c["placements"][0]))
        with self.assertRaises(AssertionError):
            validate(c)

    def test_cross_world_mismatch_rejected(self):
        c = copy.deepcopy(self.c)
        c["placements"][0]["worldId"] = "WORLD_FACTORY"
        with self.assertRaises(AssertionError):
            validate(c)

    def test_dangling_experience_rejected(self):
        c = copy.deepcopy(self.c)
        c["lessons"][0]["experienceId"] = "NOT_FOUND"
        with self.assertRaises(AssertionError):
            validate(c)

    def test_missing_default_translation_rejected(self):
        c = copy.deepcopy(self.c)
        del c["localizations"]["en-US"]["lesson.astro"]
        with self.assertRaises(AssertionError):
            validate(c)

if __name__ == "__main__":
    unittest.main()

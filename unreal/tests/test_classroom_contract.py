#!/usr/bin/env python3
"""Pure-data classroom contract checks: not Unreal Automation Tests."""
import json
import unittest
from pathlib import Path
from uuid import UUID
from datetime import datetime

FIXTURE = Path(__file__).resolve().parents[1] / "contracts" / "classroom.session.v1.example.json"

class ClassroomContractTest(unittest.TestCase):
    def test_session_roster_is_scoped_and_not_an_authentication_secret(self):
        x = json.loads(FIXTURE.read_text(encoding="utf-8"))
        self.assertEqual(1, x["schemaVersion"])
        for key in ("sessionId", "deviceId", "classroomId"):
            UUID(x[key])
        self.assertLess(datetime.fromisoformat(x["notBefore"].replace("Z", "+00:00")),
                        datetime.fromisoformat(x["expiresAt"].replace("Z", "+00:00")))
        ids = [UUID(p["studentId"]) for p in x["participants"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(p["nickname"].strip() for p in x["participants"]))
        self.assertFalse({"password", "email", "phone", "score"} & x.keys())
        self.assertNotIn("signature", x)
        self.assertNotIn("privateKey", x)

if __name__ == "__main__":
    unittest.main()

import json
import unittest
from pathlib import Path
from uuid import UUID

FIXTURE = Path(__file__).resolve().parents[1] / "contracts" / "learner-journey.v1.example.json"

class LearnerJourneyContractTest(unittest.TestCase):
    def test_three_step_order_and_locale_scoped_lesson(self):
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        self.assertEqual(1, data["schemaVersion"])
        self.assertEqual(["STUDENT_NUMBER", "LANGUAGE", "LESSON_LIST", "LESSON_RUNNING"],
                         data["screens"])
        self.assertTrue(100 <= data["studentNumber"] <= 9999)
        UUID(data["sessionId"])
        self.assertIn(data["selectedLanguage"], data["availableLanguages"])
        self.assertTrue(data["availableLessons"])
        for lesson in data["availableLessons"]:
            self.assertEqual(data["assignedCollectionId"], lesson["collectionId"])
            self.assertTrue(lesson["lessonId"])
            self.assertTrue(lesson["releaseId"])
        self.assertFalse({"nickname", "password", "authorizationToken", "devicePrivateKey"} & data.keys())

if __name__ == "__main__":
    unittest.main()

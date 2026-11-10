package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceLearningSelectionPolicyTest {
    @Test void learnerSelectsLocaleAfterEnteringNumberNotDuringDeviceActivation() {
        var session = UUID.randomUUID();
        var student = UUID.randomUUID();
        var kazakh = new DeviceLearningSelectionPolicy.LearnerChoice(session, student, "kk-KZ", "PHY-001");
        var russian = new DeviceLearningSelectionPolicy.LearnerChoice(session, student, "ru-RU", "PHY-001");
        assertNotEquals(kazakh.locale(), russian.locale());
        assertEquals(student, kazakh.studentId());
        assertThrows(IllegalArgumentException.class, () ->
                new DeviceLearningSelectionPolicy.LearnerChoice(session, student, "fr-FR", "PHY-001"));
    }

    @Test void lessonListFilteredByCollectionEntitlementPublicationAndLearnerLanguage() {
        var lessons = List.of(
            new DeviceLearningSelectionPolicy.LessonChoice("PHY-001", "PHYSICS", "Электрические цепи",
                    "rel-1", true, true, Set.of("ru-RU", "kk-KZ")),
            new DeviceLearningSelectionPolicy.LessonChoice("PHY-002", "PHYSICS", "Черновик",
                    "rel-2", false, true, Set.of("ru-RU")),
            new DeviceLearningSelectionPolicy.LessonChoice("PHY-003", "PHYSICS", "Без лицензии",
                    "rel-3", true, false, Set.of("ru-RU")),
            new DeviceLearningSelectionPolicy.LessonChoice("CHEM-001", "CHEMISTRY", "Химия",
                    "rel-4", true, true, Set.of("ru-RU")),
            new DeviceLearningSelectionPolicy.LessonChoice("PHY-004", "PHYSICS", "Electricity",
                    "rel-5", true, true, Set.of("en-US"))
        );
        var shown = DeviceLearningSelectionPolicy.visibleLessons("ru-RU", "PHYSICS", lessons);
        assertEquals(List.of("PHY-001"), shown.stream().map(
                DeviceLearningSelectionPolicy.LessonChoice::lessonId).toList());
        assertEquals("PHY-001", DeviceLearningSelectionPolicy
                .requireLesson("kk-KZ", "PHYSICS", lessons, "PHY-001").lessonId());
        for (String denied : List.of("PHY-002", "PHY-003", "CHEM-001", "PHY-004")) {
            assertThrows(SecurityException.class, () ->
                DeviceLearningSelectionPolicy.requireLesson("ru-RU", "PHYSICS", lessons, denied));
        }
    }
}

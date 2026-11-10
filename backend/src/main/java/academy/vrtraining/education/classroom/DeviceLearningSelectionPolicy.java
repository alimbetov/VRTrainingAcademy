package academy.vrtraining.education.classroom;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * R3 learner journey: number -> locale -> entitled lesson -> attempt.
 * Locale is a learner-session choice, never a global device setting.
 * This policy consumes trusted server-side publication and entitlement results.
 */
public final class DeviceLearningSelectionPolicy {
    private DeviceLearningSelectionPolicy() {}

    public static final Set<String> SUPPORTED_LOCALES = Set.of("kk-KZ", "ru-RU", "en-US");

    public record LearnerChoice(UUID sessionId, UUID studentId, String locale, String lessonId) {
        public LearnerChoice {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(studentId);
            locale = requireLocale(locale);
            requireCode(lessonId);
        }
    }

    public record LessonChoice(String lessonId, String collectionId, String localizedTitle,
                               String releaseId, boolean published, boolean entitled,
                               Set<String> locales) {
        public LessonChoice {
            requireCode(lessonId);
            requireCode(collectionId);
            requireCode(releaseId);
            if (localizedTitle == null || localizedTitle.isBlank())
                throw new IllegalArgumentException("Lesson title required");
            locales = Set.copyOf(Objects.requireNonNull(locales));
            if (locales.isEmpty() || !SUPPORTED_LOCALES.containsAll(locales))
                throw new IllegalArgumentException("Invalid lesson locales");
        }
    }

    public static String requireLocale(String locale) {
        if (!SUPPORTED_LOCALES.contains(locale))
            throw new IllegalArgumentException("Unsupported learner locale");
        return locale;
    }

    public static List<LessonChoice> visibleLessons(String learnerLocale, String collectionId,
                                                   List<LessonChoice> candidates) {
        requireLocale(learnerLocale);
        requireCode(collectionId);
        return List.copyOf(Objects.requireNonNull(candidates)).stream()
                .filter(c -> c.collectionId().equals(collectionId)
                        && c.published() && c.entitled()
                        && c.locales().contains(learnerLocale))
                .toList();
    }

    public static LessonChoice requireLesson(String learnerLocale, String collectionId,
                                              List<LessonChoice> candidates, String lessonId) {
        requireCode(lessonId);
        return visibleLessons(learnerLocale, collectionId, candidates).stream()
                .filter(c -> c.lessonId().equals(lessonId))
                .findFirst().orElseThrow(() ->
                        new SecurityException("Lesson unavailable for this learner session"));
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || code.equals(".") || code.equals(".."))
            throw new IllegalArgumentException("Invalid lesson/collection/release ID");
    }
}

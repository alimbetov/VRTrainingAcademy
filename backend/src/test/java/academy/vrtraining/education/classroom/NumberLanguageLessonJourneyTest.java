package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;
import academy.vrtraining.education.classroom.ClassroomContracts.*;
import academy.vrtraining.education.classroom.DeviceLearningSelectionPolicy.LessonChoice;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NumberLanguageLessonJourneyTest {
    private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
    private final UUID device = UUID.randomUUID(), sessionId = UUID.randomUUID(),
            classroomId = UUID.randomUUID(), studentId = UUID.randomUUID();
    private final Session session = new Session(sessionId, classroomId, device, "UNASSIGNED",
            "revision-1", NOW.minusSeconds(60), NOW.plusSeconds(1800), true);
    private final Classroom classroom = new Classroom(classroomId, "school-1", "7A", "teacher-1");
    private final ClassroomStore store = new ClassroomStore() {
        public Classroom createClassroom(String tenant, String title, String teacher) { throw new UnsupportedOperationException(); }
        public Optional<Classroom> findClassroom(UUID id) { return id.equals(classroomId) ? Optional.of(classroom) : Optional.empty(); }
        public Student createStudent(UUID id, String nick) { throw new UnsupportedOperationException(); }
        public List<Student> students(UUID id) { return List.of(new Student(studentId, classroomId, "247", true)); }
        public Session createSession(UUID id, UUID device, String lesson, String revision, Instant start, Instant end) { throw new UnsupportedOperationException(); }
        public Optional<Session> findSession(UUID id) { return id.equals(sessionId) ? Optional.of(session) : Optional.empty(); }
        public Attempt beginAttempt(UUID id, UUID s, UUID device, Instant now) { throw new UnsupportedOperationException(); }
        public Optional<Attempt> findAttempt(UUID id) { return Optional.empty(); }
        public boolean appendEvent(ProgressEvent event) { throw new UnsupportedOperationException(); }
        public List<Score> scores(UUID id) { return List.of(); }
    };
    private final ClassroomAuthorization auth = new ClassroomAuthorization() {
        public boolean teacherMayCreateClassroom(String teacher, String tenant) { return false; }
        public boolean teacherMayManage(String teacher, String tenant, UUID classroom) { return false; }
        public boolean deviceMayAttend(UUID d, String tenant, UUID s) {
            return d.equals(device) && tenant.equals("school-1") && s.equals(sessionId);
        }
    };
    private final NumberLanguageLessonJourney.Repository repo = new NumberLanguageLessonJourney.Repository() {
        public UUID studentByNumber(UUID id, int number) {
            return classroomId.equals(id) && number == 247 ? studentId : null;
        }
        public String assignedCollection(UUID id) { return sessionId.equals(id) ? "PHYSICS" : null; }
        public Attempt beginSelectedAttempt(UUID id, UUID d, UUID s, String collection, String locale, String lesson, String release, Instant now) {
            assertEquals("PHYSICS", collection);
            assertEquals(sessionId, id);
            assertEquals(device, d);
            assertEquals(studentId, s);
            assertEquals("kk-KZ", locale);
            assertEquals("PHY-001", lesson);
            assertEquals("release-1", release);
            return new Attempt(UUID.randomUUID(), id, s, d, now, "revision-1");
        }
    };
    private final AuthorizedLessonCatalog catalog = (tenant, d, collection, locale) -> List.of(
            new LessonChoice("PHY-001", "PHYSICS", "Электр тізбегі", "release-1",
                    true, true, Set.of("kk-KZ", "ru-RU")),
            new LessonChoice("PHY-SECRET", "PHYSICS", "Secret", "release-2",
                    true, false, Set.of("kk-KZ")),
            new LessonChoice("CHEM-001", "CHEMISTRY", "Chem", "release-3",
                    true, true, Set.of("kk-KZ")));
    private final NumberLanguageLessonJourney service = new NumberLanguageLessonJourney(
            store, auth, catalog, repo, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void validNumberThenLanguageThenLessonStartsBoundAttempt() {
        var menu = service.lessons("school-1", device, sessionId, 247, "kk-KZ");
        assertEquals(List.of("PHY-001"),
                menu.lessons().stream().map(LessonChoice::lessonId).toList());
        var attempt = service.start("school-1", device,
                new NumberLanguageLessonJourney.Start(sessionId, 247, "kk-KZ", "PHY-001"));
        assertEquals(studentId, attempt.studentId());
        assertEquals(sessionId, attempt.sessionId());
    }

    @Test void rejectsForgedLessonAndUnknownNumber() {
        assertThrows(SecurityException.class, () -> service.start("school-1", device,
                new NumberLanguageLessonJourney.Start(sessionId, 247, "kk-KZ", "PHY-SECRET")));
        assertThrows(SecurityException.class, () -> service.start("school-1", device,
                new NumberLanguageLessonJourney.Start(sessionId, 247, "kk-KZ", "CHEM-001")));
        assertThrows(SecurityException.class, () -> service.lessons("school-1", device, sessionId, 248, "kk-KZ"));
        assertThrows(IllegalArgumentException.class, () ->
                service.lessons("school-1", device, sessionId, 247, "fr-FR"));
    }

    @Test void rejectsWrongDeviceAndTenantBeforeCatalogLookup() {
        assertThrows(SecurityException.class, () -> service.lessons("school-2", device, sessionId, 247, "kk-KZ"));
        assertThrows(SecurityException.class, () -> service.lessons("school-1", UUID.randomUUID(), sessionId, 247, "kk-KZ"));
    }

    @Test void expiredSessionMustNotRevealLessonsOrStartAttempt() {
        var expired = new NumberLanguageLessonJourney(store, auth, catalog, repo,
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        assertThrows(SecurityException.class, () ->
                expired.lessons("school-1", device, sessionId, 247, "kk-KZ"));
        assertThrows(SecurityException.class, () ->
                expired.start("school-1", device,
                        new NumberLanguageLessonJourney.Start(sessionId, 247, "kk-KZ", "PHY-001")));
    }

    @Test void denyAllCatalogCannotStartARelease() {
        var closed = new NumberLanguageLessonJourney(store, auth, (t,d,c,l)->List.of(), repo,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertTrue(closed.lessons("school-1", device, sessionId, 247, "kk-KZ").lessons().isEmpty());
        assertThrows(SecurityException.class, () -> closed.start("school-1", device,
                new NumberLanguageLessonJourney.Start(sessionId, 247, "kk-KZ", "PHY-001")));
    }
}

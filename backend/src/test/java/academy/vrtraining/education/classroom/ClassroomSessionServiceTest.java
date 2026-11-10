package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;

import academy.vrtraining.education.classroom.ClassroomContracts.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClassroomSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
    private final FakeStore db = new FakeStore();
    private final UUID device = UUID.randomUUID();
    private final ClassroomAuthorization rights = new ClassroomAuthorization() {
        @Override public boolean teacherMayManage(String teacher, String tenant, UUID classroom) {
            return teacher.equals("teacher-1") && tenant.equals("school-1");
        }
        @Override public boolean deviceMayAttend(UUID id, String tenant, UUID session) {
            return id.equals(device) && tenant.equals("school-1");
        }
    };
    private final ClassroomSessionService service =
        new ClassroomSessionService(db, rights, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void teacherCreatesNicknameRosterAndDeviceSelectsLearner() {
        var classroom = service.createClassroom("school-1", "teacher-1", "7A");
        var alan = service.addStudent("school-1", "teacher-1", classroom.id(), "Алан");
        var aisha = service.addStudent("school-1", "teacher-1", classroom.id(), "Айша");
        var lesson = service.schedule("school-1", "teacher-1", classroom.id(), device, "physics",
                "r1", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        var view = service.roster("school-1", device, lesson.id());
        assertEquals(Set.of(alan.id(), aisha.id()), Set.of(
                view.participants().get(0).studentId(), view.participants().get(1).studentId()));
        var attempt = service.startAttempt("school-1", device,
                new Selection(lesson.id(), alan.id(), device));
        assertEquals(alan.id(), attempt.studentId());
        assertEquals("r1", attempt.lessonRevision());
        var event = new ProgressEvent(UUID.randomUUID(), attempt.id(), 1, "TASK_COMPLETED", 20, NOW);
        assertTrue(service.record("school-1", device, event).created());
        assertFalse(service.record("school-1", device, event).created());
        assertEquals(20, service.statistics("school-1", "teacher-1", classroom.id(),
                lesson.id()).getFirst().score());
    }

    @Test void crossTenantAndUnknownStudentMustFail() {
        var classroom = service.createClassroom("school-1", "teacher-1", "7A");
        var lesson = service.schedule("school-1", "teacher-1", classroom.id(), device, "physics",
                "r1", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertThrows(SecurityException.class,
                () -> service.roster("school-2", device, lesson.id()));
        assertThrows(SecurityException.class,
                () -> service.roster("school-1", UUID.randomUUID(), lesson.id()));
        assertThrows(SecurityException.class,
                () -> service.startAttempt("school-1", device,
                        new Selection(lesson.id(), UUID.randomUUID(), device)));
        assertThrows(SecurityException.class,
                () -> service.addStudent("school-2", "teacher-1", classroom.id(), "Bad"));
    }

    @Test void inactiveSessionAndWrongDeviceAreDenied() {
        var classroom = service.createClassroom("school-1", "teacher-1", "7A");
        var student = service.addStudent("school-1", "teacher-1", classroom.id(), "Alan");
        var future = service.schedule("school-1", "teacher-1", classroom.id(), device,
                "physics", "r1", NOW.plusSeconds(1800), NOW.plusSeconds(3600));
        assertThrows(SecurityException.class,
                () -> service.startAttempt("school-1", device,
                        new Selection(future.id(), student.id(), device)));
        assertThrows(SecurityException.class,
                () -> service.startAttempt("school-1", device,
                        new Selection(future.id(), student.id(), UUID.randomUUID())));
    }

    @Test void invalidEventsAndDuplicateCollisionAreRejected() {
        var classroom = service.createClassroom("school-1", "teacher-1", "7A");
        var student = service.addStudent("school-1", "teacher-1", classroom.id(), "Alan");
        var lesson = service.schedule("school-1", "teacher-1", classroom.id(), device, "physics",
                "r1", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        var attempt = service.startAttempt("school-1", device,
                new Selection(lesson.id(), student.id(), device));
        var id = UUID.randomUUID();
        var e = new ProgressEvent(id, attempt.id(), 1, "TASK_COMPLETED", 20, NOW);
        assertTrue(service.record("school-1", device, e).created());
        assertThrows(IllegalStateException.class,
                () -> service.record("school-1", device,
                        new ProgressEvent(id, attempt.id(), 1, "TASK_COMPLETED", 30, NOW)));
        assertThrows(IllegalArgumentException.class,
                () -> service.record("school-1", device,
                        new ProgressEvent(UUID.randomUUID(), attempt.id(), 2, "TASK_COMPLETED", -5, NOW)));
        assertThrows(SecurityException.class,
                () -> service.record("school-2", device, e));
    }

    private static final class FakeStore implements ClassroomStore {
        final Map<UUID, Classroom> classes = new HashMap<>();
        final Map<UUID, Student> students = new HashMap<>();
        final Map<UUID, Session> sessions = new HashMap<>();
        final Map<UUID, Attempt> attempts = new HashMap<>();
        final Map<UUID, ProgressEvent> events = new HashMap<>();

        @Override public Classroom createClassroom(String tenant, String title, String teacher) {
            var x = new Classroom(UUID.randomUUID(), tenant, title, teacher);
            classes.put(x.id(), x); return x;
        }
        @Override public Optional<Classroom> findClassroom(UUID id) { return Optional.ofNullable(classes.get(id)); }
        @Override public Student createStudent(UUID id, String nick) {
            var s = new Student(UUID.randomUUID(), id, nick, true);
            students.put(s.id(), s); return s;
        }
        @Override public List<Student> students(UUID id) {
            return students.values().stream().filter(s -> s.classroomId().equals(id)).toList();
        }
        @Override public Session createSession(UUID classroom, UUID device, String lesson,
                String revision, Instant start, Instant end) {
            var s = new Session(UUID.randomUUID(), classroom, device, lesson, revision, start, end, true);
            sessions.put(s.id(), s); return s;
        }
        @Override public Optional<Session> findSession(UUID id) { return Optional.ofNullable(sessions.get(id)); }
        @Override public Attempt beginAttempt(UUID session, UUID student, UUID device, Instant now) {
            var s = sessions.get(session);
            var attempt = new Attempt(UUID.randomUUID(), session, student, device, now, s.lessonRevision());
            attempts.put(attempt.id(), attempt); return attempt;
        }
        @Override public Optional<Attempt> findAttempt(UUID id) { return Optional.ofNullable(attempts.get(id)); }
        @Override public boolean appendEvent(ProgressEvent e) {
            var prior = events.putIfAbsent(e.eventId(), e);
            if (prior != null && !prior.equals(e)) throw new IllegalStateException("Event collision");
            return prior == null;
        }
        @Override public List<Score> scores(UUID sessionId) {
            return attempts.values().stream().filter(a -> a.sessionId().equals(sessionId))
                .map(a -> {
                    var list = events.values().stream().filter(e -> e.attemptId().equals(a.id())).toList();
                    int points = list.stream().mapToInt(ProgressEvent::scoreDelta).sum();
                    return new Score(a.studentId(), a.id(), points, list.size(),
                            list.stream().map(ProgressEvent::occurredAt)
                                    .max(Comparator.naturalOrder()).orElse(a.startedAt()));
                }).toList();
        }
    }
}

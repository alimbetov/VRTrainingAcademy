package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;
import academy.vrtraining.education.classroom.ClassroomContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeacherCollectionAssignmentServiceTest {
    private final UUID classroomId = UUID.randomUUID(), sessionId = UUID.randomUUID(),
            deviceId = UUID.randomUUID();
    private final Classroom classroom = new Classroom(classroomId, "school-1", "7A", "teacher-1");
    private final Session session = new Session(sessionId, classroomId, deviceId, "UNASSIGNED",
            "r1", Instant.parse("2026-11-10T12:00:00Z"),
            Instant.parse("2026-11-10T13:00:00Z"), true);
    private final ClassroomStore store = new ClassroomStore() {
        public Classroom createClassroom(String t, String n, String s) { throw new UnsupportedOperationException(); }
        public Optional<Classroom> findClassroom(UUID id) { return classroomId.equals(id) ? Optional.of(classroom) : Optional.empty(); }
        public Student createStudent(UUID id, String nick) { throw new UnsupportedOperationException(); }
        public List<Student> students(UUID id) { return List.of(); }
        public Session createSession(UUID id, UUID dev, String lesson, String rev, Instant a, Instant b) { throw new UnsupportedOperationException(); }
        public Optional<Session> findSession(UUID id) { return sessionId.equals(id) ? Optional.of(session) : Optional.empty(); }
        public Attempt beginAttempt(UUID id, UUID student, UUID dev, Instant at) { throw new UnsupportedOperationException(); }
        public Optional<Attempt> findAttempt(UUID id) { return Optional.empty(); }
        public boolean appendEvent(ProgressEvent e) { return false; }
        public List<Score> scores(UUID id) { return List.of(); }
    };
    private final ClassroomAuthorization authority = new ClassroomAuthorization() {
        public boolean teacherMayCreateClassroom(String teacher, String tenant) { return false; }
        public boolean teacherMayManage(String teacher, String tenant, UUID id) {
            return "teacher-1".equals(teacher) && "school-1".equals(tenant) && classroomId.equals(id);
        }
        public boolean deviceMayAttend(UUID d, String tenant, UUID id) { return false; }
    };

    @Test void teacherCanAssignOnlyAllowedPublishedCollection() {
        var service = new TeacherCollectionAssignmentService(store, authority,
                (tenant, device, collection) -> tenant.equals("school-1")
                        && device.equals(deviceId) && collection.equals("PHYSICS"),
                (s,c,d,k) -> s.equals(sessionId) && c.equals(classroomId)
                        && d.equals(deviceId) && k.equals("PHYSICS"));
        assertDoesNotThrow(() -> service.assign("school-1", "teacher-1",
                classroomId, sessionId, "PHYSICS"));
        assertThrows(SecurityException.class, () -> service.assign("school-1", "teacher-1",
                classroomId, sessionId, "CHEMISTRY"));
        assertThrows(SecurityException.class, () -> service.assign("school-2", "teacher-1",
                classroomId, sessionId, "PHYSICS"));
        assertThrows(SecurityException.class, () -> service.assign("school-1", "student",
                classroomId, sessionId, "PHYSICS"));
    }

    @Test void denyByDefaultAndConcurrentChangeFailClosed() {
        var closed = new TeacherCollectionAssignmentService(store, authority,
                (t,d,c)->false, (s,c,d,k)->true);
        assertThrows(SecurityException.class, () -> closed.assign("school-1",
                "teacher-1", classroomId, sessionId, "PHYSICS"));
        var stale = new TeacherCollectionAssignmentService(store, authority,
                (t,d,c)->true, (s,c,d,k)->false);
        assertThrows(SecurityException.class, () -> stale.assign("school-1",
                "teacher-1", classroomId, sessionId, "PHYSICS"));
    }
}

package academy.vrtraining.education.classroom;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import academy.vrtraining.education.classroom.ClassroomContracts.*;

/** Atomic repository operations: implementations MUST enforce tenant boundaries. */
public interface ClassroomStore {
    Classroom createClassroom(String tenantId, String title, String teacherSubject);
    Optional<Classroom> findClassroom(UUID id);
    Student createStudent(UUID classroomId, String nickname);
    default NumberedStudent assignNumber(UUID classroomId, int number) {
        throw new UnsupportedOperationException("Numbered student store not enabled");
    }
    List<Student> students(UUID classroomId);
    Session createSession(UUID classroomId, UUID deviceId, String lessonId,
                          String revision, java.time.Instant startsAt, java.time.Instant endsAt);
    Optional<Session> findSession(UUID sessionId);
    Attempt beginAttempt(UUID sessionId, UUID studentId, UUID deviceId, java.time.Instant now);
    Optional<Attempt> findAttempt(UUID attemptId);
    /** Idempotent insert on eventId; reject eventId collisions with different payload. */
    boolean appendEvent(ProgressEvent event);
    List<Score> scores(UUID sessionId);
    default List<Score> scoresForDay(UUID classroomId, java.time.LocalDate teachingDate) {
        throw new UnsupportedOperationException("Daily reporting adapter not enabled");
    }
}

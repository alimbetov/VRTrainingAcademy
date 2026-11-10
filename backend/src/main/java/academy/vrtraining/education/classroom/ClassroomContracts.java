package academy.vrtraining.education.classroom;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * No student password or personal account is required. Nickname is display
 * data only; UUID is the stable assessment identity scoped to tenant/class.
 */
public final class ClassroomContracts {
    private ClassroomContracts() {}

    public record Student(UUID id, UUID classroomId, String nickname, boolean active) {}
    public record NumberedStudent(UUID id, UUID classroomId, int number, boolean active) {}
    public record Classroom(UUID id, String tenantId, String title, String teacherSubject) {}
    public record Session(UUID id, UUID classroomId, UUID deviceId, String lessonId,
                          String lessonRevision, Instant startsAt, Instant endsAt, boolean active) {}
    public record Participant(UUID studentId, String nickname) {}
    public record Selection(UUID sessionId, UUID studentId, UUID deviceId) {}
    public record Attempt(UUID id, UUID sessionId, UUID studentId, UUID deviceId,
                          Instant startedAt, String lessonRevision) {}
    public record ProgressEvent(UUID eventId, UUID attemptId, int sequence,
                                String eventType, int scoreDelta, Instant occurredAt) {}
    public record ProgressReceipt(UUID eventId, boolean created) {}
    public record Score(UUID studentId, UUID attemptId, int score, int events, Instant lastAt) {}
    public record DeviceClassroomView(Session session, List<Participant> participants) {}
}

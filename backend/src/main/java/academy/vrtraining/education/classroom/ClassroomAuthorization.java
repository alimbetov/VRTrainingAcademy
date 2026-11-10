package academy.vrtraining.education.classroom;

import java.util.UUID;

/** Explicit authorization boundary, not a client-provided claim of authority. */
public interface ClassroomAuthorization {
    boolean teacherMayCreateClassroom(String teacherSubject, String tenantId);
    boolean teacherMayManage(String teacherSubject, String tenantId, UUID classroomId);
    boolean deviceMayAttend(UUID deviceId, String tenantId, UUID sessionId);
}

package academy.vrtraining.education.classroom;

import academy.vrtraining.education.classroom.ClassroomContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Teacher-only management API. Deliberately opt-in:
 * no live endpoint until PostgreSQL and verified tenant/teacher membership exist.
 */
@RestController
@ConditionalOnProperty(name = "vr.classroom.persistence-enabled", havingValue = "true")
@RequestMapping("/api/v1/classrooms")
public class TeacherClassroomController {
    private final ClassroomSessionService service;

    public TeacherClassroomController(ClassroomSessionService service) {
        this.service = service;
    }

    public record CreateClassroom(@NotBlank String title) {}
    public record AddStudent(@NotBlank String nickname) {}
    public record AssignNumber(@NotNull Integer number) {}
    public record Schedule(@NotNull UUID deviceId, @NotBlank String lessonId,
                           @NotBlank String lessonRevision, @NotNull Instant startsAt,
                           @NotNull Instant endsAt) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Classroom create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateClassroom request) {
        return service.createClassroom(tenant(jwt), jwt.getSubject(), request.title());
    }

    @PostMapping("/{classroomId}/students")
    @ResponseStatus(HttpStatus.CREATED)
    public Student addStudent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classroomId,
                              @Valid @RequestBody AddStudent request) {
        return service.addStudent(tenant(jwt), jwt.getSubject(), classroomId, request.nickname());
    }

    @PostMapping("/{classroomId}/student-numbers")
    @ResponseStatus(HttpStatus.CREATED)
    public NumberedStudent assignNumber(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId, @Valid @RequestBody AssignNumber request) {
        return service.assignNumber(tenant(jwt), jwt.getSubject(), classroomId, request.number());
    }

    @PostMapping("/{classroomId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Session schedule(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classroomId,
                            @Valid @RequestBody Schedule request) {
        return service.schedule(tenant(jwt), jwt.getSubject(), classroomId,
                request.deviceId(), request.lessonId(), request.lessonRevision(),
                request.startsAt(), request.endsAt());
    }

    @GetMapping("/{classroomId}/statistics/today")
    public List<Score> today(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classroomId) {
        return service.statisticsToday(tenant(jwt), jwt.getSubject(), classroomId);
    }

    @GetMapping("/{classroomId}/sessions/{sessionId}/statistics")
    public List<Score> statistics(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID classroomId, @PathVariable UUID sessionId) {
        return service.statistics(tenant(jwt), jwt.getSubject(), classroomId, sessionId);
    }

    private static String tenant(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null
                || !jwt.hasClaim("tenant_id") || jwt.getClaimAsString("tenant_id") == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trusted teacher tenant required");
        return jwt.getClaimAsString("tenant_id");
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void denied() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void badInput() {}
}

package academy.vrtraining.education.classroom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in teacher endpoint; default collection authorization is DENY. */
@RestController
@ConditionalOnBean(TeacherCollectionAssignmentService.class)
@RequestMapping("/api/v1/classrooms")
public class TeacherCollectionAssignmentController {
    private final TeacherCollectionAssignmentService service;

    public TeacherCollectionAssignmentController(TeacherCollectionAssignmentService service) {
        this.service = service;
    }

    public record CollectionSelection(@NotBlank String collectionId) {}

    @PutMapping("/{classroomId}/sessions/{sessionId}/collection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classroomId,
                       @PathVariable UUID sessionId, @Valid @RequestBody CollectionSelection request) {
        if (jwt == null || jwt.getSubject() == null || !jwt.hasClaim("tenant_id")
                || jwt.getClaimAsString("tenant_id") == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trusted teacher required");
        service.assign(jwt.getClaimAsString("tenant_id"), jwt.getSubject(),
                classroomId, sessionId, request.collectionId());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void denied() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalid() {}
}

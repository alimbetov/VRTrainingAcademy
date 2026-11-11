package academy.vrtraining.platform.content.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative authoring API. All actor claims derive from verified bearer
 * JWT; roles and world scope are checked against trusted PostgreSQL membership.
 * No endpoint creates memberships or publishes unsigned Quest releases.
 */
@RestController
@ConditionalOnProperty(name="vr.content.admin.enabled", havingValue="true")
@RequestMapping("/api/v1/admin/content")
public class ContentAdminController {
    public record Create(@NotBlank @Size(max=128) String code, int sortOrder) {}
    public record Localize(@NotBlank String locale, @NotBlank @Size(max=160) String title) {}
    public record ChangeStatus(@NotNull String state, long expectedVersion) {}
    public record Reorder(int sortOrder, long expectedVersion) {}

    private final PostgresContentAdministrationService service;

    public ContentAdminController(PostgresContentAdministrationService service) {
        this.service = service;
    }

    private static String verifiedSubject(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified JWT required");
        return jwt.getSubject();
    }

    @GetMapping("/worlds")
    public java.util.List<PostgresContentAdministrationService.NodeView> worlds(
            @AuthenticationPrincipal Jwt jwt) {
        return service.worlds(verifiedSubject(jwt));
    }

    @GetMapping("/worlds/{worldId}/areas")
    public java.util.List<PostgresContentAdministrationService.NodeView> areas(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID worldId) {
        return service.areas(verifiedSubject(jwt), worldId);
    }

    @GetMapping("/areas/{areaId}/subjects")
    public java.util.List<PostgresContentAdministrationService.NodeView> subjects(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID areaId) {
        return service.subjects(verifiedSubject(jwt), areaId);
    }

    @GetMapping("/subjects/{subjectId}/lessons")
    public java.util.List<PostgresContentAdministrationService.LessonView> lessons(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID subjectId) {
        return service.lessons(verifiedSubject(jwt), subjectId);
    }

    @PatchMapping("/{kind}/{nodeId}/order")
    public PostgresContentAdministrationService.Change reorder(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
            @PathVariable UUID nodeId, @Valid @RequestBody Reorder body) {
        return service.reorder(verifiedSubject(jwt), kind, nodeId,
                body.sortOrder(), body.expectedVersion());
    }

    @PostMapping("/worlds")
    @ResponseStatus(HttpStatus.CREATED)
    public PostgresContentAdministrationService.Created world(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create body) {
        return service.createWorld(verifiedSubject(jwt), body.code(), body.sortOrder());
    }

    @PostMapping("/worlds/{worldId}/areas")
    @ResponseStatus(HttpStatus.CREATED)
    public PostgresContentAdministrationService.Created area(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID worldId,
            @Valid @RequestBody Create body) {
        return service.createArea(verifiedSubject(jwt), worldId, body.code(), body.sortOrder());
    }

    @PostMapping("/areas/{areaId}/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    public PostgresContentAdministrationService.Created subject(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID areaId,
            @Valid @RequestBody Create body) {
        return service.createSubject(verifiedSubject(jwt), areaId, body.code(), body.sortOrder());
    }

    @PostMapping("/subjects/{subjectId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public PostgresContentAdministrationService.Created lesson(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID subjectId,
            @Valid @RequestBody Create body) {
        return service.createLesson(verifiedSubject(jwt), subjectId, body.code(), body.sortOrder());
    }

    @PostMapping("/lessons/{lessonId}/placements/{subjectId}")
    @ResponseStatus(HttpStatus.CREATED)
    public PostgresContentAdministrationService.Created place(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID lessonId,
            @PathVariable UUID subjectId, @Valid @RequestBody Reorder body) {
        return service.addPlacement(verifiedSubject(jwt),lessonId,subjectId,body.sortOrder());
    }

    @PutMapping("/{kind}/{nodeId}/translations/{locale}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void translate(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
                          @PathVariable UUID nodeId, @PathVariable String locale,
                          @Valid @RequestBody Localize body) {
        if (!locale.equals(body.locale()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Locale mismatch");
        service.translate(verifiedSubject(jwt), kind, nodeId, locale, body.title());
    }

    @PostMapping("/{kind}/{nodeId}/transitions")
    public PostgresContentAdministrationService.Change transition(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
            @PathVariable UUID nodeId, @Valid @RequestBody ChangeStatus body) {
        return service.transition(verifiedSubject(jwt), kind, nodeId, body.state(),
                body.expectedVersion());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden() {}

    @ExceptionHandler(PostgresContentAdministrationService.Conflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalid() {}
}

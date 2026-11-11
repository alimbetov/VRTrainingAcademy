package academy.vrtraining.platform.content.upload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated authoring only. No client supplied tenant/collection/release IDs. */
@RestController
@ConditionalOnProperty(name="vr.content.upload-sessions.enabled",havingValue="true")
@RequestMapping("/api/v1/admin/content")
public class LessonUploadController {
    private final LessonUploadSessions sessions;
    public LessonUploadController(LessonUploadSessions sessions){this.sessions=sessions;}
    private static String actor(Jwt jwt){
        if(jwt==null||jwt.getSubject()==null||jwt.getSubject().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"OIDC user required");
        return jwt.getSubject();
    }
    public record Create(@NotNull UUID subjectId) {}

    @PostMapping("/lessons/{lessonId}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public LessonUploadSessions.Session create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID lessonId,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Create body){
        return sessions.create(actor(jwt),lessonId,body.subjectId(),key);
    }
    @GetMapping("/uploads/{uploadId}")
    public LessonUploadSessions.Session status(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID uploadId){
        return sessions.status(actor(jwt),uploadId);
    }
    @PostMapping(path="/uploads/{uploadId}/artifact",
                 consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LessonUploadSessions.Staged artifact(@AuthenticationPrincipal Jwt jwt,
             @PathVariable UUID uploadId,
             @RequestPart("metadata") LessonUploadSessions.ArtifactRequest metadata,
             @RequestPart("artifact") MultipartFile artifact) throws IOException {
        if(metadata==null || artifact==null || artifact.isEmpty()
                || !artifact.getOriginalFilename().equals(metadata.artifactName()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Artifact filename mismatch");
        try(var stream=artifact.getInputStream()){
            return sessions.stage(actor(jwt),uploadId,metadata,stream);
        }
    }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden(){}
    @ExceptionHandler(LessonUploadSessions.Conflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict(){}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalid(){}
    @ExceptionHandler(IOException.class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public void storageUnavailable(){}
}

package academy.vrtraining.platform.content;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Private owner/authoring upload, disabled unless quarantine storage is configured. */
@RestController
@ConditionalOnBean(QuestLessonUploadService.class)
@RequestMapping("/api/v1/content/quest/staging")
public class QuestLessonUploadController {
    private final QuestLessonUploadService uploads;

    public QuestLessonUploadController(QuestLessonUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public QuestLessonUploadService.Receipt upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("metadata") QuestLessonUploadService.Request metadata,
            @RequestPart("artifact") MultipartFile artifact) {
        if (jwt == null || jwt.getSubject() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated author required");
        if (artifact.isEmpty() || artifact.getOriginalFilename() == null
                || !artifact.getOriginalFilename().equals(metadata.artifactName()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact filename mismatch");
        try (var in = artifact.getInputStream()) {
            return uploads.stage(jwt.getSubject(), metadata, in);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Uploader not authorized", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid staged artifact", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quarantine upload unavailable", e);
        }
    }
}

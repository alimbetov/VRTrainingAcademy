package academy.vrtraining.platform.content;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Explicit opt-in for quarantine upload. By default all uploads are unavailable.
 * IAM membership must be supplied separately; the fallback is DENY.
 */
@Configuration
public class QuestLessonUploadConfiguration {
    @Bean
    @ConditionalOnProperty(name="vr.upload.enabled", havingValue="true")
    QuestLessonUploadService questLessonUploadService(
            S3Client client,
            org.springframework.beans.factory.ObjectProvider<QuestLessonUploadService.UploadAuthorization> auth,
            @Value("${vr.upload.quarantine-bucket}") String bucket) {
        if (bucket.isBlank()) throw new IllegalArgumentException("Quarantine bucket required");
        var authorization = auth.getIfAvailable(() -> (subject, organization) -> false);
        return new QuestLessonUploadService(authorization, (file, key) -> {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("application/octet-stream").build(), RequestBody.fromFile(file));
        });
    }
}

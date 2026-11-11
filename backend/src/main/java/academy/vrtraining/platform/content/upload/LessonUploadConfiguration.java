package academy.vrtraining.platform.content.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Disabled until R5.1/R5.2/R5.4 DB migrations, OIDC and S3 are deployed. */
@Configuration
@ConditionalOnProperty(name="vr.content.upload-sessions.enabled",havingValue="true")
public class LessonUploadConfiguration {
    @Bean LessonUploadSessions lessonUploadSessions(
          S3Client client,
          @Value("${vr.content.upload-sessions.jdbc-url:}") String url,
          @Value("${vr.content.upload-sessions.jdbc-user:}") String user,
          @Value("${vr.content.upload-sessions.jdbc-password:}") String password,
          @Value("${vr.content.upload-sessions.quarantine-bucket:}") String bucket) {
        if(bucket.isBlank())throw new IllegalArgumentException("Quarantine bucket is required");
        return new LessonUploadSessions(url,user,password,(file,key)->
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
               .contentType("application/octet-stream").build(),RequestBody.fromFile(file)));
    }
}

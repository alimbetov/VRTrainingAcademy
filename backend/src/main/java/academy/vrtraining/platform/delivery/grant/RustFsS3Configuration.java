package academy.vrtraining.platform.delivery.grant;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Real S3/RustFS signing is only enabled with explicit configuration. */
@Configuration
public class RustFsS3Configuration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vr.delivery.s3-enabled", havingValue = "true")
    S3Presigner rustFsPresigner(
            @Value("${vr.delivery.base-url}") URI endpoint,
            @Value("${vr.delivery.region:us-east-1}") String region) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null)
            throw new IllegalArgumentException("RustFS presigner requires HTTPS trusted origin");
        return S3Presigner.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vr.delivery.s3-enabled", havingValue = "true")
    S3Client rustFsS3Client(@Value("${vr.delivery.base-url}") URI endpoint,
                           @Value("${vr.delivery.region:us-east-1}") String region) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null)
            throw new IllegalArgumentException("RustFS client requires HTTPS trusted origin");
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(DownloadGrantService.ArtifactUrlSigner.class)
    DownloadGrantService.ArtifactUrlSigner grantSigner(
            org.springframework.beans.factory.ObjectProvider<S3Presigner> presigner) {
        return (bucket, key, ttl) -> {
            var signer = presigner.getIfAvailable();
            if (signer == null) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "RustFS not configured");
            var request = GetObjectPresignRequest.builder().signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build();
            return signer.presignGetObject(request).url().toURI();
        };
    }
}

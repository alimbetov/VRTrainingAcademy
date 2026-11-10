package academy.vrtraining.platform.delivery.grant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import software.amazon.awssdk.services.s3.S3Client;

/** Actual controller HTTP contract, without downloading object bytes. */
class BackendArtifactProxyControllerTest {
    private static final String SHA = "a".repeat(64);
    private BackendArtifactProxyController controller;
    private Jwt jwt;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var service = mock(DownloadGrantService.class);
        var artifact = new PublishedReleasePort.Artifact("lesson.pak", "releases/lesson.pak", 1000, SHA);
        var release = new PublishedReleasePort.PublishedRelease(
                "lesson", "release-1", "quest", "build-1", "content",
                List.of(artifact), "manifest", "signature");
        when(service.requireAuthorized(anyString(), anyString(), anyString(), anyString(),
                anyString())).thenReturn(release);
        ObjectProvider<S3Client> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(S3Client.class));
        controller = new BackendArtifactProxyController(service, provider,
                DownloadGrantService.Transport.BACKEND_PROXY);
        jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject("teacher").claim("tenant_id", "school-1").build();
    }

    @Test void fullDownloadExposesStrongEtag() {
        var response = controller.download(jwt, "lesson", "lesson.pak", "quest", "build-1", null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("\"" + SHA + "\"", response.getHeaders().getETag());
        assertEquals(1000, response.getHeaders().getContentLength());
    }

    @Test void resumedDownloadSetsExactRangeAndEtag() {
        var response = controller.download(jwt, "lesson", "lesson.pak", "quest", "build-1",
                "bytes=500-", "\"" + SHA + "\"");
        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals(500, response.getHeaders().getContentLength());
        assertEquals("bytes 500-999/1000",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test void mismatchedIfRangeSafelyReturnsFullContent() {
        var response = controller.download(jwt, "lesson", "lesson.pak", "quest", "build-1",
                "bytes=500-", "\"old\"");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1000, response.getHeaders().getContentLength());
    }

    @Test void invalidRangeReturns416AndTotal() {
        var response = controller.download(jwt, "lesson", "lesson.pak", "quest", "build-1",
                "bytes=1200-", null);
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }
}

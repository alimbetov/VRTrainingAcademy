package academy.vrtraining.platform.delivery.grant;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Authenticated fallback for closed corporate networks. Never exposes object
 * keys as client-supplied input. This route authorizes on EVERY GET (and Range).
 */
@RestController
@RequestMapping("/api/v1/me/content")
public class BackendArtifactProxyController {
    private final DownloadGrantService service;
    private final ObjectProvider<S3Client> s3;
    private final DownloadGrantService.Transport transport;

    public BackendArtifactProxyController(DownloadGrantService service, ObjectProvider<S3Client> s3,
            @Value("${vr.delivery.transport:DIRECT_RUSTFS}") DownloadGrantService.Transport transport) {
        this.service = service;
        this.s3 = s3;
        this.transport = transport;
    }

    @GetMapping("/{contentId}/artifacts/{artifactName}")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String contentId,
            @PathVariable String artifactName, @RequestParam String platform,
            @RequestParam String runtimeBuildId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (transport != DownloadGrantService.Transport.BACKEND_PROXY)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (jwt == null || jwt.getSubject() == null || !jwt.hasClaim("tenant_id"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trusted tenant required");
        var release = service.requireAuthorized(jwt.getSubject(), jwt.getClaimAsString("tenant_id"),
                contentId, platform, runtimeBuildId);
        var artifact = release.artifacts().stream()
                .filter(a -> a.name().equals(artifactName))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        S3Client client = s3.getIfAvailable();
        if (client == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RustFS unavailable");

        long size = artifact.sizeBytes();
        long start = 0, end = size - 1;
        if (range != null) {
            if (!range.matches("bytes=[0-9]+-[0-9]*"))
                throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            String[] parts = range.substring(6).split("-", -1);
            try {
                start = Long.parseLong(parts[0]);
                if (!parts[1].isBlank()) end = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            }
            if (start >= size || end < start || end >= size)
                throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
        if (size <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Empty artifact not proxyable");
        long from = start, to = end;
        String s3Range = "bytes=" + from + "-" + to;
        StreamingResponseBody body = output -> {
            GetObjectRequest.Builder builder = GetObjectRequest.builder()
                    .bucket(release.bucket()).key(artifact.objectKey());
            if (range != null) builder.range(s3Range);
            try (InputStream stream = client.getObject(builder.build())) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = to - from + 1;
                int count;
                while (remaining > 0 && (count = stream.read(buffer, 0,
                        (int) Math.min(buffer.length, remaining))) != -1) {
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                if (remaining != 0) throw new java.io.IOException("RustFS short read");
            }
        };
        var headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(to - from + 1);
        if (range != null) headers.set(HttpHeaders.CONTENT_RANGE,
                "bytes " + from + "-" + to + "/" + size);
        return new ResponseEntity<>(body, headers,
                range == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT);
    }
}

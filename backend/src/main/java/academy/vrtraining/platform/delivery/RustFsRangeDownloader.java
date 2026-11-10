package academy.vrtraining.platform.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTP(S) byte-range downloader reference adapter for RustFS/S3 presigned GET URLs.
 * Requests must be resolved and authorized by the caller before this adapter sees them.
 * Never log a presigned URL (including its query string).
 */
public final class RustFsRangeDownloader {
    private final HttpClient client;
    private final URI allowedOrigin;
    private final boolean allowPlainHttpForTests;

    public RustFsRangeDownloader(URI baseUrl, boolean allowPlainHttpForTests) {
        this.allowedOrigin = Objects.requireNonNull(baseUrl, "baseUrl");
        this.allowPlainHttpForTests = allowPlainHttpForTests;
        requireAllowed(baseUrl);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private void requireAllowed(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !(uri.getScheme().equalsIgnoreCase("https")
                || (allowPlainHttpForTests && uri.getScheme().equalsIgnoreCase("http"))))
            throw new IllegalArgumentException("Invalid RustFS URL");
        if (!uri.getScheme().equalsIgnoreCase(allowedOrigin.getScheme())
                || !uri.getHost().equalsIgnoreCase(allowedOrigin.getHost())
                || effectivePort(uri) != effectivePort(allowedOrigin))
            throw new IllegalArgumentException("Different RustFS origin");
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    /**
     * Returns verified-size downloaded path; hash validation still belongs to
     * SignedReleaseVerifier before installation. Partial files are resumable.
     */
    public Path download(URI presignedGetUrl, Path partialFile, long expectedBytes)
            throws IOException, InterruptedException {
        requireAllowed(presignedGetUrl);
        if (expectedBytes < 0) throw new IllegalArgumentException("expectedBytes");
        Files.createDirectories(partialFile.toAbsolutePath().getParent());
        if (Files.isSymbolicLink(partialFile)) throw new IOException("Unsafe partial file");
        Path etagFile = partialFile.resolveSibling(partialFile.getFileName() + ".etag");
        if (Files.isSymbolicLink(etagFile)) throw new IOException("Unsafe ETag file");
        long already = Files.exists(partialFile) ? Files.size(partialFile) : 0;
        if (already > expectedBytes) {
            reset(partialFile, etagFile);
            already = 0;
        }
        if (already == expectedBytes && already > 0) return partialFile;

        HttpRequest.Builder request = HttpRequest.newBuilder(presignedGetUrl)
                .GET().timeout(Duration.ofMinutes(5))
                .header("Accept-Encoding", "identity");
        if (already > 0) {
            if (!Files.isRegularFile(etagFile)) {
                reset(partialFile, etagFile);
                already = 0;
            } else {
                String etag = Files.readString(etagFile);
                if (etag.isBlank() || etag.contains("\n") || etag.contains("\r")
                        || etag.startsWith("W/")) {
                    reset(partialFile, etagFile);
                    already = 0;
                } else {
                    request.header("Range", "bytes=" + already + "-");
                    request.header("If-Range", etag);
                }
            }
        }

        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        try (InputStream input = response.body()) {
            int status = response.statusCode();
            if (status != 200 && status != 206)
                throw new IOException("RustFS GET failed: HTTP " + status);
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("identity");
            if (!"identity".equalsIgnoreCase(contentEncoding))
                throw new IOException("Unexpected content encoding");

            boolean append = status == 206 && already > 0;
            if (status == 206) {
                String range = response.headers().firstValue("Content-Range").orElse("");
                if (!range.equals("bytes " + already + "-" + (expectedBytes - 1) + "/" + expectedBytes))
                    throw new IOException("Unexpected Content-Range");
                String oldEtag = Files.exists(etagFile) ? Files.readString(etagFile) : "";
                String newEtag = response.headers().firstValue("ETag").orElse("");
                if (append && !oldEtag.equals(newEtag))
                    throw new IOException("ETag changed on resume");
            }
            if (status == 200) {
                if (already > 0) {
                    // Server ignored Range or object changed. Restart from byte zero.
                    reset(partialFile, etagFile);
                }
                String etag = response.headers().firstValue("ETag").orElse("");
                if (!etag.isBlank() && !etag.startsWith("W/")
                        && !etag.contains("\r") && !etag.contains("\n"))
                    Files.writeString(etagFile, etag);
            }
            try (var output = Files.newOutputStream(partialFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    append ? java.nio.file.StandardOpenOption.APPEND
                           : java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                long count = append ? already : 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > expectedBytes) throw new IOException("Content exceeds manifest size");
                    output.write(buffer, 0, read);
                }
                if (count != expectedBytes) throw new IOException("Incomplete HTTP transfer");
            }
            return partialFile;
        }
    }

    private static void reset(Path partial, Path etag) throws IOException {
        Files.deleteIfExists(partial);
        Files.deleteIfExists(etag);
    }
}

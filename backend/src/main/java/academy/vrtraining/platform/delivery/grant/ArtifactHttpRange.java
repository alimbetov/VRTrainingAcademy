package academy.vrtraining.platform.delivery.grant;

import java.util.Objects;

/**
 * Immutable published-artifact HTTP range contract.
 * Strong ETag derives from verified SHA-256, not S3 multipart ETag.
 * If-Range mismatch MUST downgrade to full 200 rather than splice files.
 */
public final class ArtifactHttpRange {
    private ArtifactHttpRange() {}

    public record Selection(long from, long to, long total, boolean partial, String etag) {
        public long length() { return to - from + 1; }
        public String contentRange() {
            return partial ? "bytes " + from + "-" + to + "/" + total : null;
        }
        public String s3Range() { return "bytes=" + from + "-" + to; }
    }

    public static Selection select(long size, String sha256Hex, String range, String ifRange) {
        if (size <= 0) throw new IllegalArgumentException("Empty artifact not downloadable");
        if (sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid immutable artifact hash");
        String etag = "\"" + sha256Hex + "\"";
        if (range == null || (ifRange != null && !Objects.equals(ifRange, etag)))
            return new Selection(0, size - 1, size, false, etag);
        if (!range.matches("bytes=[0-9]+-[0-9]*"))
            throw new InvalidRangeException(size);
        String[] positions = range.substring(6).split("-", -1);
        try {
            long start = Long.parseLong(positions[0]);
            long end = positions[1].isEmpty() ? size - 1 : Long.parseLong(positions[1]);
            if (start < 0 || start >= size || end < start || end >= size)
                throw new InvalidRangeException(size);
            return new Selection(start, end, size, true, etag);
        } catch (NumberFormatException e) {
            throw new InvalidRangeException(size);
        }
    }

    public static final class InvalidRangeException extends RuntimeException {
        private final long total;
        public InvalidRangeException(long total) {
            super("Unsatisfiable byte range");
            this.total = total;
        }
        public long total() { return total; }
    }
}

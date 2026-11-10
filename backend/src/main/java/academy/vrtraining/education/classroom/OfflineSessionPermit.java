package academy.vrtraining.education.classroom;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * V1 device-bound signed OFFLINE CLASSROOM PERMIT. Permit is authorization to
 * start a specified scheduled lesson offline, NOT proof of student identity.
 * Public key is pinned by the client, private key stays on trusted server.
 */
public final class OfflineSessionPermit {
    private OfflineSessionPermit() {}

    public record Payload(UUID sessionId, UUID deviceId, String tenantId, String lessonId,
                          String lessonRevision, Instant notBefore, Instant expiresAt,
                          List<UUID> allowedStudentIds) {
        public Payload {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(deviceId);
            requireAscii(tenantId);
            requireAscii(lessonId);
            requireAscii(lessonRevision);
            Objects.requireNonNull(notBefore);
            Objects.requireNonNull(expiresAt);
            allowedStudentIds = List.copyOf(allowedStudentIds);
            if (!expiresAt.isAfter(notBefore)
                    || expiresAt.isAfter(notBefore.plusSeconds(8 * 3600))
                    || allowedStudentIds.isEmpty() || allowedStudentIds.size() > 100
                    || allowedStudentIds.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(allowedStudentIds).size() != allowedStudentIds.size())
                throw new IllegalArgumentException("Invalid offline permit");
        }
    }

    public static byte[] canonical(Payload p) {
        try {
            var output = new ByteArrayOutputStream();
            var out = new DataOutputStream(output);
            out.writeUTF("VRTA-CLASSROOM-PERMIT-V1");
            writeUuid(out, p.sessionId());
            writeUuid(out, p.deviceId());
            out.writeUTF(p.tenantId());
            out.writeUTF(p.lessonId());
            out.writeUTF(p.lessonRevision());
            out.writeLong(p.notBefore().toEpochMilli());
            out.writeLong(p.expiresAt().toEpochMilli());
            var sorted = p.allowedStudentIds().stream().sorted(
                    Comparator.comparing(UUID::toString)).toList();
            out.writeInt(sorted.size());
            for (var studentId : sorted) writeUuid(out, studentId);
            return output.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Invalid permit encoding", e); }
    }

    public static byte[] sign(Payload p, PrivateKey signingKey) throws GeneralSecurityException {
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(Objects.requireNonNull(signingKey));
        signature.update(canonical(p));
        return signature.sign();
    }

    public static void verify(Payload p, byte[] detachedSignature, PublicKey trustedKey,
                              UUID actualDeviceId, String tenantId, Clock clock)
            throws GeneralSecurityException {
        Objects.requireNonNull(trustedKey);
        Objects.requireNonNull(clock);
        if (!p.deviceId().equals(actualDeviceId) || !p.tenantId().equals(tenantId))
            throw new GeneralSecurityException("Permit scope mismatch");
        Instant now = clock.instant();
        if (now.isBefore(p.notBefore()) || !now.isBefore(p.expiresAt()))
            throw new GeneralSecurityException("Offline permit outside validity window");
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(trustedKey);
        verifier.update(canonical(p));
        if (!verifier.verify(detachedSignature))
            throw new GeneralSecurityException("Invalid permit signature");
    }

    public static void requireStudent(Payload p, UUID selectedStudentId)
            throws GeneralSecurityException {
        if (!p.allowedStudentIds().contains(selectedStudentId))
            throw new GeneralSecurityException("Student not on authorized offline roster");
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void requireAscii(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid permit identifier");
    }
}

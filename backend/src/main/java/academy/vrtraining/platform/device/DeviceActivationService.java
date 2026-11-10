package academy.vrtraining.platform.device;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Foundation of device registration. OAuth Device Authorization is provided by
 * an external OIDC authorization server; this component is the PLATFORM
 * device-binding step after a trusted administrator login/consent.
 *
 * Never issue full access solely because an activation code was guessed.
 * Complete requires proof of possession of the device's private key.
 */
public final class DeviceActivationService {
    public enum State { PENDING, APPROVED, ACTIVE, REVOKED }

    public record Activation(UUID flowId, String userCode, byte[] challenge, Instant expiresAt) {}
    public record Device(UUID deviceId, String tenantId, PublicKey publicKey, State state) {}
    public record Pending(UUID flowId, byte[] codeDigest, byte[] challenge, PublicKey publicKey,
                          Instant expiresAt, String tenantId, State state) {}

    public interface Store {
        void insert(Pending pending);
        Pending findPending(UUID flowId);
        /** Atomic compare-and-swap PENDING to APPROVED (code is one-use). */
        boolean approve(UUID flowId, byte[] codeDigest, String tenantId, Instant now);
        /** Atomic compare-and-swap APPROVED to ACTIVE and create device key binding. */
        Device activate(UUID flowId, Instant now);
        Device findDevice(UUID deviceId);
        boolean revoke(UUID deviceId, String tenantId);
    }

    public interface AdministratorAuthorization {
        boolean mayActivateDevice(String adminSubject, String tenantId);
        boolean mayRevokeDevice(String adminSubject, String tenantId);
    }

    private final Store store;
    private final AdministratorAuthorization admin;
    private final Clock clock;
    private final SecureRandom random;
    private final byte[] codePepper;

    public DeviceActivationService(Store store, AdministratorAuthorization admin, Clock clock,
                                   SecureRandom random, byte[] codePepper) {
        this.store = Objects.requireNonNull(store);
        this.admin = Objects.requireNonNull(admin);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
        if (codePepper == null || codePepper.length < 32)
            throw new IllegalArgumentException("At least 32 bytes secret code pepper required");
        this.codePepper = codePepper.clone();
    }

    public Activation begin(PublicKey devicePublicKey) throws GeneralSecurityException {
        Objects.requireNonNull(devicePublicKey);
        if (!"EdDSA".equalsIgnoreCase(devicePublicKey.getAlgorithm())
                && !"Ed25519".equalsIgnoreCase(devicePublicKey.getAlgorithm()))
            throw new GeneralSecurityException("Ed25519 device key required");
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        byte[] entropy = new byte[9];
        random.nextBytes(entropy);
        StringBuilder code = new StringBuilder(9);
        for (int i = 0; i < 8; i++) code.append(alphabet.charAt((entropy[i] & 0xff) % alphabet.length()));
        String visible = code.substring(0, 4) + "-" + code.substring(4);
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        UUID flowId = UUID.randomUUID();
        Instant expiry = clock.instant().plus(Duration.ofMinutes(10));
        store.insert(new Pending(flowId, codeDigest(visible), challenge.clone(), devicePublicKey,
                expiry, null, State.PENDING));
        return new Activation(flowId, visible, challenge.clone(), expiry);
    }

    /** Called only after authenticating administrator via trusted OIDC. */
    public void approve(String adminSubject, String tenantId, UUID flowId, String code)
            throws GeneralSecurityException {
        if (!admin.mayActivateDevice(adminSubject, tenantId))
            throw new GeneralSecurityException("Not authorized to activate device");
        if (code == null || !code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}")
                || !store.approve(flowId, codeDigest(code), tenantId, clock.instant()))
            throw new GeneralSecurityException("Invalid or expired activation");
    }

    /** Called by untrusted device; signature establishes possession of its key. */
    public Device complete(UUID flowId, byte[] proof) throws GeneralSecurityException {
        Pending pending = store.findPending(flowId);
        if (pending == null || pending.state() != State.APPROVED
                || !clock.instant().isBefore(pending.expiresAt()))
            throw new GeneralSecurityException("Device activation not approved");
        var signature = Signature.getInstance("Ed25519");
        signature.initVerify(pending.publicKey());
        signature.update(bindingMessage(flowId, pending.challenge()));
        if (proof == null || !signature.verify(proof))
            throw new GeneralSecurityException("Invalid device key proof");
        Device device = store.activate(flowId, clock.instant());
        if (device == null || device.state() != State.ACTIVE)
            throw new GeneralSecurityException("Activation replay");
        return device;
    }

    public void revoke(String adminSubject, String tenantId, UUID deviceId)
            throws GeneralSecurityException {
        if (!admin.mayRevokeDevice(adminSubject, tenantId)
                || !store.revoke(deviceId, tenantId))
            throw new GeneralSecurityException("Device revocation rejected");
    }

    public static byte[] bindingMessage(UUID flowId, byte[] challenge) {
        byte[] prefix = ("VRTA-DEVICE-ACTIVATE-V1:" + flowId + ":")
                .getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + challenge.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(challenge, 0, message, prefix.length, challenge.length);
        return message;
    }

    private byte[] codeDigest(String code) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(codePepper, "HmacSHA256"));
        return mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
    }
}

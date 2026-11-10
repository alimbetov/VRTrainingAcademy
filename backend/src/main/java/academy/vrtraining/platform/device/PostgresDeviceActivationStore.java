package academy.vrtraining.platform.device;

import academy.vrtraining.platform.device.DeviceActivationService.*;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Transactional PostgreSQL adapter for one-use device activation. */
public final class PostgresDeviceActivationStore implements DeviceActivationService.Store {
    private final String url, user, password;

    public PostgresDeviceActivationStore(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("Postgres connection required");
        this.url = url;
        this.user = java.util.Objects.requireNonNull(user);
        this.password = java.util.Objects.requireNonNull(password);
    }

    private java.sql.Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
    private static RuntimeException failed(Exception e) {
        return new IllegalStateException("Device activation persistence failed", e);
    }
    private static PublicKey decode(byte[] encoded) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) { throw failed(e); }
    }
    private static State state(ResultSet r, String name) throws SQLException {
        return State.valueOf(r.getString(name));
    }
    private static Pending fromRow(ResultSet r) throws SQLException {
        return new Pending((UUID)r.getObject("flow_id"), r.getBytes("code_digest"),
                r.getBytes("challenge"), decode(r.getBytes("public_key_spki")),
                r.getTimestamp("expires_at").toInstant(),
                r.getString("tenant_id"), state(r, "state"));
    }

    @Override public void insert(Pending p) {
        try (var db = connect();
             var q = db.prepareStatement(
                "INSERT INTO platform.device_activation_flow(flow_id,code_digest,challenge,public_key_spki,expires_at,state) VALUES(?,?,?,?,?,?)")) {
            q.setObject(1, p.flowId());
            q.setBytes(2, p.codeDigest());
            q.setBytes(3, p.challenge());
            q.setBytes(4, p.publicKey().getEncoded());
            q.setTimestamp(5, Timestamp.from(p.expiresAt()));
            q.setString(6, State.PENDING.name());
            q.executeUpdate();
        } catch (SQLException e) { throw failed(e); }
    }

    @Override public Pending findPending(UUID flowId) {
        try (var db = connect();
             var q = db.prepareStatement(
                "SELECT * FROM platform.device_activation_flow WHERE flow_id=?")) {
            q.setObject(1, flowId);
            try (var r = q.executeQuery()) { return r.next() ? fromRow(r) : null; }
        } catch (SQLException e) { throw failed(e); }
    }

    @Override public boolean approve(UUID id, byte[] codeDigest, String tenantId, Instant now) {
        try (var db = connect()) {
            db.setAutoCommit(false);
            try {
                Pending pending;
                int attempts;
                try (var q = db.prepareStatement(
                        "SELECT * FROM platform.device_activation_flow WHERE flow_id=? FOR UPDATE")) {
                    q.setObject(1, id);
                    try (var r = q.executeQuery()) {
                        if (!r.next()) { db.rollback(); return false; }
                        pending = fromRow(r);
                        attempts = r.getInt("unsuccessful_attempts");
                    }
                }
                if (pending.state() != State.PENDING || !now.isBefore(pending.expiresAt())
                        || attempts >= 10) { db.rollback(); return false; }
                boolean matches = MessageDigest.isEqual(pending.codeDigest(), codeDigest);
                if (matches) {
                    try (var q = db.prepareStatement(
                        "UPDATE platform.device_activation_flow SET state='APPROVED',tenant_id=?,approved_at=? WHERE flow_id=?")) {
                        q.setString(1, tenantId);
                        q.setTimestamp(2, Timestamp.from(now));
                        q.setObject(3, id);
                        q.executeUpdate();
                    }
                } else {
                    try (var q = db.prepareStatement(
                        "UPDATE platform.device_activation_flow SET unsuccessful_attempts=unsuccessful_attempts+1 WHERE flow_id=?")) {
                        q.setObject(1, id);
                        q.executeUpdate();
                    }
                }
                db.commit();
                return matches;
            } catch (Exception e) { db.rollback(); throw e; }
        } catch (Exception e) { throw failed(e); }
    }

    @Override public Device activate(UUID id, Instant now) {
        try (var db = connect()) {
            db.setAutoCommit(false);
            try {
                Pending p;
                try (var q = db.prepareStatement(
                        "SELECT * FROM platform.device_activation_flow WHERE flow_id=? FOR UPDATE")) {
                    q.setObject(1, id);
                    try (var r = q.executeQuery()) {
                        if (!r.next()) { db.rollback(); return null; }
                        p = fromRow(r);
                    }
                }
                if (p.state() != State.APPROVED || !now.isBefore(p.expiresAt())
                        || p.tenantId() == null) { db.rollback(); return null; }
                UUID deviceId = UUID.randomUUID();
                try (var q = db.prepareStatement(
                        "INSERT INTO platform.registered_device(device_id,tenant_id,public_key_spki,state,activated_at) VALUES(?,?,?,'ACTIVE',?)")) {
                    q.setObject(1, deviceId);
                    q.setString(2, p.tenantId());
                    q.setBytes(3, p.publicKey().getEncoded());
                    q.setTimestamp(4, Timestamp.from(now));
                    q.executeUpdate();
                }
                try (var q = db.prepareStatement(
                        "UPDATE platform.device_activation_flow SET state='ACTIVE',activated_device_id=? WHERE flow_id=?")) {
                    q.setObject(1, deviceId);
                    q.setObject(2, id);
                    q.executeUpdate();
                }
                db.commit();
                return new Device(deviceId, p.tenantId(), p.publicKey(), State.ACTIVE);
            } catch (Exception e) { db.rollback(); throw e; }
        } catch (Exception e) { throw failed(e); }
    }

    @Override public Device findDevice(UUID id) {
        try (var db = connect();
             var q = db.prepareStatement(
                    "SELECT device_id,tenant_id,public_key_spki,state FROM platform.registered_device WHERE device_id=?")) {
            q.setObject(1, id);
            try (var r = q.executeQuery()) {
                return r.next() ? new Device((UUID)r.getObject("device_id"),
                        r.getString("tenant_id"), decode(r.getBytes("public_key_spki")),
                        state(r,"state")) : null;
            }
        } catch (SQLException e) { throw failed(e); }
    }

    @Override public boolean revoke(UUID id, String tenantId) {
        try (var db = connect();
             var q = db.prepareStatement(
                    "UPDATE platform.registered_device SET state='REVOKED',revoked_at=now() " +
                    "WHERE device_id=? AND tenant_id=? AND state='ACTIVE'")) {
            q.setObject(1, id);
            q.setString(2, tenantId);
            return q.executeUpdate() == 1;
        } catch (SQLException e) { throw failed(e); }
    }
}

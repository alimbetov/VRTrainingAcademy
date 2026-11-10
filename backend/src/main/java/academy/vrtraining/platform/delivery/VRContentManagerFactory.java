package academy.vrtraining.platform.delivery;

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Environment bootstrap for the JVM reference on-device manager.
 * Missing RUSTFS_BASE_URL means browsing is allowed but downloads are unavailable.
 * The application must never load private signing keys on a learner device.
 */
public final class VRContentManagerFactory {
    private VRContentManagerFactory() {}

    public static VRContentManager fromEnvironment(Map<String, String> env) throws Exception {
        String origin = required(env, "RUSTFS_BASE_URL");
        String key = required(env, "VR_CONTENT_SIGNING_PUBLIC_KEY_B64");
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
        int limit = Integer.parseInt(env.getOrDefault("VR_CONTENT_MAX_INSTALLED", "3"));
        long reserve = Long.parseLong(
                env.getOrDefault("VR_CONTENT_DISK_RESERVE_BYTES", "536870912"));
        boolean localHttp = Boolean.parseBoolean(
                env.getOrDefault("VR_CONTENT_ALLOW_HTTP_LOCAL_TESTS", "false"));
        URI uri = URI.create(origin);
        if (localHttp && !("localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost())))
            throw new IllegalArgumentException("Plain HTTP allowed only for local tests");
        return new VRContentManager(
                Path.of(env.getOrDefault("VR_CONTENT_LOCAL_ROOT", "./.local-vr-content")),
                uri, localHttp, publicKey,
                required(env, "VR_CONTENT_PLATFORM"),
                required(env, "VR_CONTENT_RUNTIME_BUILD_ID"), reserve, limit);
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(
                key + " is not configured; VR downloads remain disabled");
        return value;
    }
}

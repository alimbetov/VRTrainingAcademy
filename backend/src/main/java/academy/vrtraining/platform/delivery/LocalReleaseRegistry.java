package academy.vrtraining.platform.delivery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * JVM reference durable inventory. State updates are temp-file + atomic rename.
 * Caller must hold the VRContentManager filesystem lock across read/modify/write.
 * This file is not an authorization store; installed content still requires
 * signed-manifest verification and a separate offline entitlement decision.
 */
public final class LocalReleaseRegistry {
    private final Path root;
    private final Path file;

    public record Entry(String releaseId, long bytes, Instant lastOpenedAt, boolean pinned,
                        boolean protectedFromEviction) {
        public Entry {
            if (releaseId == null || !releaseId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || releaseId.equals(".") || releaseId.equals("..")
                    || bytes < 0 || lastOpenedAt == null)
                throw new IllegalArgumentException("Invalid inventory entry");
        }
    }

    public LocalReleaseRegistry(Path root) {
        this.root = root;
        this.file = root.resolve("inventory.properties");
    }

    public List<Entry> read() throws IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.isSymbolicLink(file)) throw new IOException("Symlinked registry");
        var p = new Properties();
        try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
        if (!"1".equals(p.getProperty("version"))) throw new IOException("Unknown registry version");
        int count;
        try { count = Integer.parseInt(p.getProperty("count", "-1")); }
        catch (NumberFormatException e) { throw new IOException("Invalid count", e); }
        if (count < 0 || count > 10000) throw new IOException("Invalid registry count");
        List<Entry> entries = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < count; i++) {
            try {
                String prefix = "entry." + i + ".";
                Entry entry = new Entry(p.getProperty(prefix+"id"),
                        Long.parseLong(p.getProperty(prefix+"bytes")),
                        Instant.ofEpochMilli(Long.parseLong(p.getProperty(prefix+"lastOpened"))),
                        Boolean.parseBoolean(p.getProperty(prefix+"pinned", "false")),
                        Boolean.parseBoolean(p.getProperty(prefix+"protected", "false")));
                if (!seen.add(entry.releaseId())) throw new IOException("Duplicate release");
                entries.add(entry);
            } catch (RuntimeException e) { throw new IOException("Corrupt registry entry " + i, e); }
        }
        return List.copyOf(entries);
    }

    public void write(List<Entry> entries) throws IOException {
        Files.createDirectories(root);
        var p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("count", Integer.toString(entries.size()));
        var sorted = entries.stream().sorted(Comparator.comparing(Entry::releaseId)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            var entry = sorted.get(i);
            String prefix = "entry." + i + ".";
            p.setProperty(prefix+"id", entry.releaseId());
            p.setProperty(prefix+"bytes", Long.toString(entry.bytes()));
            p.setProperty(prefix+"lastOpened", Long.toString(entry.lastOpenedAt().toEpochMilli()));
            p.setProperty(prefix+"pinned", Boolean.toString(entry.pinned()));
            p.setProperty(prefix+"protected", Boolean.toString(entry.protectedFromEviction()));
        }
        Path temporary = Files.createTempFile(root, ".inventory-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temporary)) { p.store(out, "VRTA inventory v1"); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}

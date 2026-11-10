package academy.vrtraining.education.classroom;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * R3 contract: locale is set on device activation, content collection is
 * explicitly selected from the entitled, published collection list.
 * Public demo catalog is NOT an entitlement authority.
 */
public final class DeviceLearningSelectionPolicy {
    private DeviceLearningSelectionPolicy() {}

    public static final Set<String> SUPPORTED_LOCALES = Set.of("kk-KZ", "ru-RU", "en-US");

    public record DevicePreferences(java.util.UUID deviceId, String locale) {
        public DevicePreferences {
            Objects.requireNonNull(deviceId);
            locale = requireLocale(locale);
        }
    }

    public record CollectionChoice(String collectionId, String localizedTitle, String releaseId,
                                   boolean published, boolean entitled, Set<String> locales) {
        public CollectionChoice {
            requireCode(collectionId);
            requireCode(releaseId);
            if (localizedTitle == null || localizedTitle.isBlank())
                throw new IllegalArgumentException("Collection title required");
            locales = Set.copyOf(Objects.requireNonNull(locales));
            if (locales.isEmpty() || !SUPPORTED_LOCALES.containsAll(locales))
                throw new IllegalArgumentException("Invalid collection locales");
        }
    }

    public static String requireLocale(String locale) {
        if (!SUPPORTED_LOCALES.contains(locale))
            throw new IllegalArgumentException("Unsupported locale");
        return locale;
    }

    public static List<CollectionChoice> visibleCollections(String deviceLocale,
                                                            List<CollectionChoice> candidates) {
        requireLocale(deviceLocale);
        return List.copyOf(Objects.requireNonNull(candidates)).stream()
                .filter(c -> c.published() && c.entitled() && c.locales().contains(deviceLocale))
                .toList();
    }

    public static CollectionChoice requireSelection(String deviceLocale,
                                                     List<CollectionChoice> candidates,
                                                     String selectedCollectionId) {
        requireCode(selectedCollectionId);
        return visibleCollections(deviceLocale, candidates).stream()
                .filter(c -> c.collectionId().equals(selectedCollectionId))
                .findFirst().orElseThrow(() ->
                        new SecurityException("Collection unavailable for this device and locale"));
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || code.equals(".") || code.equals(".."))
            throw new IllegalArgumentException("Invalid collection/release ID");
    }
}

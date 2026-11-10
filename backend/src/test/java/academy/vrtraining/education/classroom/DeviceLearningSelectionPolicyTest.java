package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceLearningSelectionPolicyTest {
    @Test void languageIsChosenForActivatedDevice() {
        var preferences = new DeviceLearningSelectionPolicy.DevicePreferences(UUID.randomUUID(), "kk-KZ");
        assertEquals("kk-KZ", preferences.locale());
        assertThrows(IllegalArgumentException.class, () ->
                new DeviceLearningSelectionPolicy.DevicePreferences(UUID.randomUUID(), "fr-FR"));
    }

    @Test void deviceOnlySeesPublishedEntitledLocalizedCollections() {
        var candidates = List.of(
                new DeviceLearningSelectionPolicy.CollectionChoice("PHY-7", "Физика", "release-1",
                        true, true, Set.of("kk-KZ", "ru-RU")),
                new DeviceLearningSelectionPolicy.CollectionChoice("CHEM-7", "Химия", "release-2",
                        true, false, Set.of("ru-RU")),
                new DeviceLearningSelectionPolicy.CollectionChoice("DRAFT", "Черновик", "release-3",
                        false, true, Set.of("ru-RU")),
                new DeviceLearningSelectionPolicy.CollectionChoice("EN", "Safety", "release-4",
                        true, true, Set.of("en-US")));
        assertEquals(1, DeviceLearningSelectionPolicy.visibleCollections("ru-RU", candidates).size());
        assertEquals("PHY-7", DeviceLearningSelectionPolicy
                .requireSelection("ru-RU", candidates, "PHY-7").collectionId());
        assertThrows(SecurityException.class, () ->
                DeviceLearningSelectionPolicy.requireSelection("ru-RU", candidates, "CHEM-7"));
        assertThrows(SecurityException.class, () ->
                DeviceLearningSelectionPolicy.requireSelection("ru-RU", candidates, "DRAFT"));
        assertThrows(SecurityException.class, () ->
                DeviceLearningSelectionPolicy.requireSelection("ru-RU", candidates, "EN"));
    }

    @Test void selectingCollectionCannotOverrideDeviceLanguage() {
        var candidates = List.of(new DeviceLearningSelectionPolicy.CollectionChoice(
                "ASTRO", "Күн жүйесі", "release-4", true, true, Set.of("kk-KZ")));
        assertThrows(SecurityException.class, () ->
                DeviceLearningSelectionPolicy.requireSelection("ru-RU", candidates, "ASTRO"));
    }
}

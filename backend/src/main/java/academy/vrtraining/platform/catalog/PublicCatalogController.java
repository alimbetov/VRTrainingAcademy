package academy.vrtraining.platform.catalog;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * R0 only: public, immutable demo catalog; no identity or paid entitlements.
 * Replaced by persisted scoped catalog in R1.
 */
@RestController
@RequestMapping("/api/v1/public/catalog")
public class PublicCatalogController {
    private static final Map<String, Map<String, String>> TITLES = Map.of(
        "ru-RU", Map.of("ASTRO-001", "Солнечная система", "PHY-001", "Электрическая цепь", "SAFE-001", "Найди опасности"),
        "kk-KZ", Map.of("ASTRO-001", "Күн жүйесі", "PHY-001", "Электр тізбегі", "SAFE-001", "Қауіптерді тап"),
        "en-US", Map.of("ASTRO-001", "Solar System", "PHY-001", "Electric Circuit", "SAFE-001", "Find the Hazards")
    );

    @GetMapping
    public CatalogResponse catalog(
        @RequestParam(defaultValue = "ru-RU")
        @Pattern(regexp = "ru-RU|kk-KZ|en-US") String locale
    ) {
        var titles = TITLES.get(locale);
        return new CatalogResponse(locale, List.of(
            new WorldDto("SCHOOL", "EDUCATION", List.of(
                new LessonDto("ASTRO-001", "ASTRONOMY", titles.get("ASTRO-001"), true),
                new LessonDto("PHY-001", "PHYSICS", titles.get("PHY-001"), true))),
            new WorldDto("FACTORY", "PROFESSIONAL", List.of(
                new LessonDto("SAFE-001", "SAFETY", titles.get("SAFE-001"), true)))
        ));
    }

    public record CatalogResponse(String locale, List<WorldDto> worlds) {}
    public record WorldDto(String code, String domain, List<LessonDto> lessons) {}
    public record LessonDto(String code, String subject, String title, boolean free) {}
}

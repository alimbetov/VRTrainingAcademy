package academy.vrtraining.education.classroom;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Never substitute public R0 demo lessons for licensed publication data. */
@Configuration
public class LessonJourneyConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthorizedLessonCatalog.class)
    AuthorizedLessonCatalog denyAllLessons() {
        return (tenant, device, collection, locale) -> List.of();
    }

    @Bean
    @ConditionalOnBean({ClassroomStore.class, NumberLanguageLessonJourney.Repository.class})
    NumberLanguageLessonJourney numberLanguageLessonJourney(
            ClassroomStore classrooms, ClassroomAuthorization authorization,
            AuthorizedLessonCatalog catalog, NumberLanguageLessonJourney.Repository repository) {
        return new NumberLanguageLessonJourney(classrooms, authorization, catalog, repository,
                Clock.systemUTC());
    }
}

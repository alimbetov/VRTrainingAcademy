package academy.vrtraining.education.classroom;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** Never substitute public R0 demo lessons for licensed publication data. */
@Configuration
public class LessonJourneyConfiguration {
    @Bean
    @ConditionalOnProperty(name="vr.classroom.persistence-enabled", havingValue="true")
    PostgresLearnerJourneyRepository postgresLearnerJourneyRepository(
            @Value("${vr.classroom.jdbc-url:}") String url,
            @Value("${vr.classroom.jdbc-user:}") String user,
            @Value("${vr.classroom.jdbc-password:}") String password) {
        return new PostgresLearnerJourneyRepository(url, user, password);
    }

    @Bean
    @ConditionalOnMissingBean(AuthorizedCollectionAccess.class)
    AuthorizedCollectionAccess denyAllCollectionAssignments() {
        return (tenant, device, collection) -> false;
    }

    @Bean
    @ConditionalOnBean({ClassroomStore.class, PostgresLearnerJourneyRepository.class})
    TeacherCollectionAssignmentService teacherCollectionAssignmentService(
            ClassroomStore classrooms, ClassroomAuthorization auth,
            AuthorizedCollectionAccess collections, PostgresLearnerJourneyRepository repository) {
        return new TeacherCollectionAssignmentService(classrooms, auth, collections, repository);
    }

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

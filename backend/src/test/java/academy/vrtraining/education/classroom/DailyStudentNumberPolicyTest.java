package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyStudentNumberPolicyTest {
    @Test void allowsNumbersWithoutNamesAndRejectsDuplicates() {
        assertEquals(247, DailyStudentNumberPolicy.requireNumber(247));
        assertThrows(IllegalArgumentException.class, () -> DailyStudentNumberPolicy.requireNumber(99));
        assertThrows(IllegalArgumentException.class, () -> DailyStudentNumberPolicy.requireNumber(10000));
        DailyStudentNumberPolicy.requireUniqueClassNumbers(List.of(104,247,318));
        assertThrows(IllegalArgumentException.class,
                () -> DailyStudentNumberPolicy.requireUniqueClassNumbers(List.of(247,247)));
    }

    @Test void statisticsResetAtSchoolLocalMidnightNotServerUtcMidnight() {
        var school = ZoneId.of("Asia/Almaty");
        var time = Instant.parse("2026-11-10T19:01:00Z");
        assertEquals(LocalDate.of(2026,11,11), DailyStudentNumberPolicy.teachingDate(time,school));
        assertTrue(DailyStudentNumberPolicy.belongsToToday(LocalDate.of(2026,11,11),
                school, Clock.fixed(time, ZoneOffset.UTC)));
        assertFalse(DailyStudentNumberPolicy.belongsToToday(LocalDate.of(2026,11,10),
                school, Clock.fixed(time, ZoneOffset.UTC)));
    }

    @Test void historicalDataRetainedThirtyDaysUnlessAcknowledgedAndNotHeld() {
        var now = Instant.parse("2026-12-10T12:00:00Z");
        var old = now.minus(java.time.Duration.ofDays(31));
        var recent = now.minus(java.time.Duration.ofDays(29));
        assertFalse(DailyStudentNumberPolicy.eligibleForCleanup(old,now,false,false));
        assertFalse(DailyStudentNumberPolicy.eligibleForCleanup(old,now,true,true));
        assertFalse(DailyStudentNumberPolicy.eligibleForCleanup(recent,now,true,false));
        assertTrue(DailyStudentNumberPolicy.eligibleForCleanup(old,now,true,false));
    }
}

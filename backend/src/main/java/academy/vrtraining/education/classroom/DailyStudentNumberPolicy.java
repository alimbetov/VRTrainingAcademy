package academy.vrtraining.education.classroom;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * R3 daily classroom decisions. Learners choose a NUMBER (not a nickname).
 * Daily reporting and 30-day storage retention are separate concerns.
 */
public final class DailyStudentNumberPolicy {
    public static final int MIN_NUMBER = 100;
    public static final int MAX_NUMBER = 9999;
    public static final int RETENTION_DAYS = 30;

    private DailyStudentNumberPolicy() {}

    public static int requireNumber(int studentNumber) {
        if (studentNumber < MIN_NUMBER || studentNumber > MAX_NUMBER)
            throw new IllegalArgumentException("Student number must be 100..9999");
        return studentNumber;
    }

    public static LocalDate teachingDate(Instant instant, ZoneId schoolTimezone) {
        if (instant == null || schoolTimezone == null)
            throw new IllegalArgumentException("Teaching date requires instant and timezone");
        return instant.atZone(schoolTimezone).toLocalDate();
    }

    public static boolean belongsToToday(LocalDate sessionDate, ZoneId schoolTimezone, Clock clock) {
        if (sessionDate == null || schoolTimezone == null || clock == null)
            throw new IllegalArgumentException("Missing date context");
        return sessionDate.equals(teachingDate(clock.instant(), schoolTimezone));
    }

    /**
     * An event can be removed only after both the retention deadline and
     * confirmed synchronization. Retention never silently deletes an unacked
     * offline attempt; that requires a separate reconciliation policy.
     */
    public static boolean eligibleForCleanup(Instant completedAt, Instant now,
                                             boolean acknowledged, boolean legalHold) {
        if (completedAt == null || now == null) throw new IllegalArgumentException("Missing time");
        return acknowledged && !legalHold
                && !completedAt.plus(java.time.Duration.ofDays(RETENTION_DAYS)).isAfter(now);
    }

    public static void requireUniqueClassNumbers(Iterable<Integer> assigned) {
        Set<Integer> seen = new HashSet<>();
        for (Integer number : assigned) {
            if (number == null || !seen.add(requireNumber(number)))
                throw new IllegalArgumentException("Duplicate or invalid student number");
        }
    }
}

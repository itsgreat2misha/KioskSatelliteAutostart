// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Plain-main tests for the pure-Java parts. Run with: java -ea -cp <classes> taskscheduler.TaskSchedulerTest */
public final class TaskSchedulerTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    public static void main(String[] args) {
        argParserTests();
        rootRunnerTests();
        pidTests();
        schedulerStateTests();
        taskSpecTests();
        scheduleTests();
        scheduleSummaryTests();
        System.out.println("TaskSchedulerTest: all checks passed");
    }

    private static void argParserTests() {
        List<String> split = ArgParser.split("/system/bin/am start -n 'com.example/.Main' \"a b\" c\\ d");
        check(split.size() == 6, "split size, got " + split);
        check(split.get(3).equals("com.example/.Main"), "single quotes");
        check(split.get(4).equals("a b"), "double quotes");
        check(split.get(5).equals("c d"), "escaped space");
        check(ArgParser.split("").isEmpty(), "empty split");
        boolean threw = false;
        try { ArgParser.split("unterminated 'quote"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "unterminated quote throws");

        List<String> argv = Arrays.asList("/system/bin/am", "start", "-n", "com.example/.Main", "a b", "it's");
        String joined = ArgParser.joinShell(argv);
        check(ArgParser.split(joined).equals(argv), "quote round-trip: " + joined);
        check(ArgParser.shellQuote("simple").equals("simple"), "safe arg unquoted");
        check(ArgParser.shellQuote("").equals("''"), "empty arg quoted");
    }

    private static void rootRunnerTests() {
        List<String> su0 = RootRunner.su0Command(Arrays.asList("/system/bin/id"));
        check(su0.equals(Arrays.asList("su", "0", "/system/bin/id")), "su 0 command: " + su0);
        List<String> suShell = RootRunner.suShellCommand(Arrays.asList("/system/bin/am", "start", "a b"));
        check(suShell.size() == 3 && suShell.get(0).equals("su") && suShell.get(1).equals("-c"), "su -c command: " + suShell);
        check(ArgParser.split(suShell.get(2)).equals(Arrays.asList("/system/bin/am", "start", "a b")), "su -c round-trip");
    }

    private static void pidTests() {
        Integer pid = Pid.current();
        check(pid != null && pid.intValue() > 0, "pid current: " + pid);
    }

    private static void schedulerStateTests() {
        // Boot record: one boot id, hashes only, fits the 512-char setting limit.
        SchedulerState empty = SchedulerState.parse("", "");
        check(!empty.isBootCompleted("abcdef12"), "empty boot state");
        SchedulerState one = empty.withBootCompleted(SchedulerState.hash("/system/bin/id", ""));
        String encoded = one.encodeBoot("11111111-2222-3333-4444-555555555555");
        check(encoded != null && encoded.length() < 100, "boot encoded length: " + encoded);
        SchedulerState parsed = SchedulerState.parse(encoded, "");
        check("11111111-2222-3333-4444-555555555555".equals(parsed.bootId()), "boot id round-trip");
        check(parsed.isBootCompleted(SchedulerState.hash("/system/bin/id", "")), "hash round-trip");
        check(!parsed.isBootCompleted(SchedulerState.hash("/system/bin/id", "changed")), "edited task changes hash");
        check(SchedulerState.parse("garbage", "").bootId() == null, "malformed boot state ignored");
        check("old-boot".equals(SchedulerState.parse("v1|old-boot|abcdef12", "").bootId()), "old boot id parsed");

        // Anchor record: round-trip at minute resolution.
        long anchorMs = at(UTC, 2026, 1, 15, 10, 30) + 45000L; // 45 s past the minute
        SchedulerState anchored = SchedulerState.empty().withAnchor("abcd1234", anchorMs);
        Set<String> live = new HashSet<String>(Arrays.asList("abcd1234"));
        String encodedAnchors = anchored.encodeAnchors(live);
        check(encodedAnchors != null, "anchor encoding fits");
        SchedulerState reparsed = SchedulerState.parse("", encodedAnchors);
        check(reparsed.anchorMillis("abcd1234") != null
                && reparsed.anchorMillis("abcd1234").longValue() == anchorMs - 45000L,
                "anchor round-trips at minute resolution, got " + reparsed.anchorMillis("abcd1234"));
        check(reparsed.anchorMillis("missing") == null, "missing anchor is null");
        check(SchedulerState.parse("", "garbage").anchorMillis("abcd1234") == null, "malformed anchors ignored");

        // Dead keys are dropped at encode time; live keys survive.
        SchedulerState two = anchored.withAnchor("deadbeef", anchorMs);
        String onlyLive = two.encodeAnchors(live);
        check(onlyLive != null && !onlyLive.contains("deadbeef"), "dead anchor key dropped: " + onlyLive);

        // Worst case: 32 anchors must fit in one 512-char setting.
        SchedulerState full = SchedulerState.empty();
        Set<String> allKeys = new HashSet<String>();
        for (int i = 0; i < 32; i++) {
            String key = SchedulerState.hash("/system/bin/task" + i, "args " + i);
            allKeys.add(key);
            full = full.withAnchor(key, anchorMs);
        }
        String worst = full.encodeAnchors(allKeys);
        check(worst != null && worst.length() <= SchedulerState.MAX_LENGTH,
                "32 anchors fit the setting limit, length " + (worst == null ? -1 : worst.length()));
        SchedulerState fullParsed = SchedulerState.parse("", worst);
        for (String key : allKeys) {
            check(fullParsed.anchorMillis(key) != null, "worst-case anchor survives: " + key);
        }
    }

    private static void taskSpecTests() {
        // Parsing, including number-as-double (how KS JSON numbers arrive).
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("label", "Rotation");
        row.put("enabled", Boolean.TRUE);
        row.put("executable", "/system/bin/settings");
        row.put("args", "put system user_rotation 1");
        row.put("backend", "Shizuku");
        row.put("schedule", "Weekly");
        row.put("intervalMinutes", Double.valueOf(45.0));
        row.put("timeOfDay", "07:05");
        row.put("weekday", "Friday");
        row.put("missedPolicy", "Skip");
        row.put("jitterMinutes", Double.valueOf(15.0));
        row.put("timeoutSeconds", Double.valueOf(10.0));
        TaskSpec spec = TaskSpec.fromRow(row);
        check(spec.argv().size() == 5, "argv size");
        check(spec.argv().get(1).equals("put"), "argv put");
        check(spec.timeoutSeconds == 10, "timeout as double");
        check("Weekly".equals(spec.schedule), "schedule parses");
        check(spec.intervalMinutes == 45, "interval as double");
        check("07:05".equals(spec.timeOfDay), "time of day parses");
        check("Friday".equals(spec.weekday), "weekday parses");
        check("Skip".equals(spec.missedPolicy), "missed policy parses");
        check(spec.jitterMinutes == 15, "jitter as double");

        // Invalid values fall back or clamp, in the existing style.
        Map<String, Object> bad = new HashMap<String, Object>();
        bad.put("executable", "/system/bin/id");
        bad.put("backend", "Nope");
        bad.put("schedule", "Hourly");
        bad.put("intervalMinutes", Double.valueOf(0.0));
        bad.put("timeOfDay", "25:00");
        bad.put("weekday", "Funday");
        bad.put("missedPolicy", "Whatever");
        bad.put("jitterMinutes", Integer.valueOf(999));
        bad.put("timeoutSeconds", Integer.valueOf(99));
        TaskSpec badSpec = TaskSpec.fromRow(bad);
        check(badSpec.backend.equals("Default"), "unknown backend becomes Default");
        check(badSpec.schedule.equals("Default"), "unknown schedule becomes Default");
        check(badSpec.intervalMinutes == Schedule.MIN_INTERVAL_MINUTES, "interval clamped to minimum");
        Map<String, Object> bigInterval = new HashMap<String, Object>();
        bigInterval.put("executable", "/system/bin/id");
        bigInterval.put("intervalMinutes", Double.valueOf(99999.0));
        check(TaskSpec.fromRow(bigInterval).intervalMinutes == Schedule.MAX_INTERVAL_MINUTES,
                "interval clamped to maximum");
        check(badSpec.timeOfDay.equals(""), "invalid time of day becomes empty");
        check(badSpec.weekday.equals("Monday"), "unknown weekday becomes Monday");
        check(badSpec.missedPolicy.equals(Schedule.CATCH_UP), "unknown missed policy becomes Catch up once");
        check(badSpec.jitterMinutes == 120, "jitter clamped to 120");
        check(badSpec.timeoutSeconds == 30, "timeout clamped to 30");
        check(badSpec.enabled, "enabled defaults to true when absent");
        check(!badSpec.run, "run toggle defaults to false when absent");

        Map<String, Object> runRow = new HashMap<String, Object>();
        runRow.put("executable", "/system/bin/id");
        runRow.put("run", Boolean.TRUE);
        check(TaskSpec.fromRow(runRow).run, "run toggle parses true");
        check(TaskSpec.fromRow(runRow).hash().equals(SchedulerState.hash("/system/bin/id", "")),
                "run toggle does not change task hash");

        // Schedule keys: a schedule edit changes the key (fresh anchor),
        // an unrelated edit does not.
        String baseKey = spec.scheduleKey(spec.schedule);
        Map<String, Object> backendEdit = new HashMap<String, Object>(row);
        backendEdit.put("backend", "Root");
        check(TaskSpec.fromRow(backendEdit).scheduleKey(spec.schedule).equals(baseKey),
                "backend edit keeps the schedule key");
        Map<String, Object> timeEdit = new HashMap<String, Object>(row);
        timeEdit.put("timeOfDay", "08:05");
        check(!TaskSpec.fromRow(timeEdit).scheduleKey(spec.schedule).equals(baseKey),
                "time-of-day edit changes the schedule key");
        Map<String, Object> intervalEdit = new HashMap<String, Object>(row);
        intervalEdit.put("intervalMinutes", Double.valueOf(90.0));
        check(!TaskSpec.fromRow(intervalEdit).scheduleKey(spec.schedule).equals(baseKey),
                "interval edit changes the schedule key");
    }

    private static void scheduleTests() {
        long t0 = at(UTC, 2026, 1, 15, 10, 0); // a Thursday

        // Interval: anchored on the last run.
        TaskSpec hourly = task("Interval", 60, "", "Monday", Schedule.CATCH_UP, 0);
        String key = hourly.scheduleKey(Schedule.INTERVAL);
        check(Schedule.nextDueMs(hourly, Schedule.INTERVAL, key, Long.valueOf(t0), t0 + 59 * 60000L, UTC) == t0 + 60 * 60000L,
                "interval next due is anchor + period");
        check(!Schedule.isDue(hourly, Schedule.INTERVAL, key, Long.valueOf(t0), t0 + 59 * 60000L, UTC), "interval not due early");
        check(Schedule.isDue(hourly, Schedule.INTERVAL, key, Long.valueOf(t0), t0 + 60 * 60000L, UTC), "interval due at anchor + period");
        check(Schedule.isDue(hourly, Schedule.INTERVAL, key, null, t0, UTC), "interval with no anchor is due immediately");

        // Interval jitter: within [0, jitter], and stable for one occurrence.
        TaskSpec jittery = task("Interval", 60, "", "Monday", Schedule.CATCH_UP, 30);
        String jitterKey = jittery.scheduleKey(Schedule.INTERVAL);
        long jitterDue = Schedule.nextDueMs(jittery, Schedule.INTERVAL, jitterKey, Long.valueOf(t0), t0, UTC);
        check(jitterDue >= t0 + 60 * 60000L && jitterDue <= t0 + 90 * 60000L,
                "jittered due within [base, base+jitter], got offset " + (jitterDue - t0 - 3600000L) / 60000L + " min");
        check(Schedule.nextDueMs(jittery, Schedule.INTERVAL, jitterKey, Long.valueOf(t0), t0 + 12345L, UTC) == jitterDue,
                "jitter offset is stable across evaluations");

        // Interval Skip: a whole period late abandons the occurrence; the
        // following occurrence becomes next due (the plugin resyncs).
        TaskSpec skipHourly = task("Interval", 60, "", "Monday", Schedule.SKIP, 0);
        String skipKey = skipHourly.scheduleKey(Schedule.INTERVAL);
        check(Schedule.nextDueMs(skipHourly, Schedule.INTERVAL, skipKey, Long.valueOf(t0), t0 + 130 * 60000L, UTC) == t0 + 120 * 60000L,
                "skip interval reports the following occurrence");
        check(Schedule.isDue(skipHourly, Schedule.INTERVAL, skipKey, Long.valueOf(t0), t0 + 90 * 60000L, UTC),
                "skip interval still due when less than a period late");

        // Daily: before and after the time of day.
        TaskSpec daily = task("Daily", 60, "02:30", "Monday", Schedule.CATCH_UP, 0);
        String dailyKey = daily.scheduleKey(Schedule.DAILY);
        long jan14run = at(UTC, 2026, 1, 14, 2, 30);
        check(Schedule.nextDueMs(daily, Schedule.DAILY, dailyKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 1, 0), UTC)
                == at(UTC, 2026, 1, 15, 2, 30), "daily next due is today's time");
        check(Schedule.isDue(daily, Schedule.DAILY, dailyKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 3, 0), UTC),
                "daily catch-up is due after the time");
        check(Schedule.nextDueMs(daily, Schedule.DAILY, dailyKey, Long.valueOf(at(UTC, 2026, 1, 15, 3, 0)), at(UTC, 2026, 1, 15, 3, 0), UTC)
                == at(UTC, 2026, 1, 16, 2, 30), "daily after a run is due tomorrow");

        // Daily Skip: only inside the skip window.
        TaskSpec dailySkip = task("Daily", 60, "02:30", "Monday", Schedule.SKIP, 0);
        String dailySkipKey = dailySkip.scheduleKey(Schedule.DAILY);
        check(Schedule.isDue(dailySkip, Schedule.DAILY, dailySkipKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 2, 40), UTC),
                "daily skip due inside the window");
        check(!Schedule.isDue(dailySkip, Schedule.DAILY, dailySkipKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 3, 0), UTC),
                "daily skip not due after the window");
        check(Schedule.nextDueMs(dailySkip, Schedule.DAILY, dailySkipKey, Long.valueOf(jan14run), at(UTC, 2026, 1, 15, 3, 0), UTC)
                == at(UTC, 2026, 1, 16, 2, 30), "daily skip next due is tomorrow");

        // Daily with no valid time never fires.
        TaskSpec dailyNoTime = task("Daily", 60, "", "Monday", Schedule.CATCH_UP, 0);
        check(Schedule.nextDueMs(dailyNoTime, Schedule.DAILY, dailyNoTime.scheduleKey(Schedule.DAILY), null, t0, UTC) == -1,
                "daily without a time never fires");

        // Weekly: same weekday, and the wrap to next week after a run.
        // 2026-01-14 is a Wednesday.
        TaskSpec weekly = task("Weekly", 60, "09:00", "Wednesday", Schedule.CATCH_UP, 0);
        String weeklyKey = weekly.scheduleKey(Schedule.WEEKLY);
        long jan7run = at(UTC, 2026, 1, 7, 9, 0);
        check(Schedule.nextDueMs(weekly, Schedule.WEEKLY, weeklyKey, Long.valueOf(jan7run), at(UTC, 2026, 1, 14, 8, 0), UTC)
                == at(UTC, 2026, 1, 14, 9, 0), "weekly next due is this week's weekday");
        check(Schedule.isDue(weekly, Schedule.WEEKLY, weeklyKey, Long.valueOf(jan7run), at(UTC, 2026, 1, 16, 12, 0), UTC),
                "weekly catch-up is due later in the week");
        check(Schedule.nextDueMs(weekly, Schedule.WEEKLY, weeklyKey, Long.valueOf(at(UTC, 2026, 1, 14, 9, 5)), at(UTC, 2026, 1, 14, 10, 0), UTC)
                == at(UTC, 2026, 1, 21, 9, 0), "weekly wraps to next week after a run");

        // DST spring forward (2026-03-08, America/New_York): 02:30 does
        // not exist; Calendar resolves it forward to 03:30 EDT.
        TaskSpec dstDaily = task("Daily", 60, "02:30", "Monday", Schedule.CATCH_UP, 0);
        String dstKey = dstDaily.scheduleKey(Schedule.DAILY);
        long dstDue = Schedule.nextDueMs(dstDaily, Schedule.DAILY, dstKey,
                Long.valueOf(at(NEW_YORK, 2026, 3, 7, 2, 30)), at(NEW_YORK, 2026, 3, 7, 12, 0), NEW_YORK);
        Calendar dstCal = Calendar.getInstance(NEW_YORK);
        dstCal.setTimeInMillis(dstDue);
        check(dstCal.get(Calendar.DAY_OF_MONTH) == 8 && dstCal.get(Calendar.HOUR_OF_DAY) == 3
                && dstCal.get(Calendar.MINUTE) == 30,
                "spring-forward daily resolves to Mar 8 03:30, got " + dstCal.getTime());

        // DST fall back (2026-11-01): 01:30 happens twice; Calendar
        // resolves the ambiguous time to the second (standard-time,
        // EST, UTC-5) occurrence, which is the documented behaviour.
        TaskSpec fallDaily = task("Daily", 60, "01:30", "Monday", Schedule.CATCH_UP, 0);
        String fallKey = fallDaily.scheduleKey(Schedule.DAILY);
        long fallDue = Schedule.nextDueMs(fallDaily, Schedule.DAILY, fallKey,
                Long.valueOf(at(NEW_YORK, 2026, 10, 31, 1, 30)), at(NEW_YORK, 2026, 10, 31, 12, 0), NEW_YORK);
        Calendar fallCal = Calendar.getInstance(NEW_YORK);
        fallCal.setTimeInMillis(fallDue);
        check(fallCal.get(Calendar.DAY_OF_MONTH) == 1 && fallCal.get(Calendar.HOUR_OF_DAY) == 1
                && fallCal.get(Calendar.MINUTE) == 30 && NEW_YORK.getOffset(fallDue) == -5L * 3600L * 1000L,
                "fall-back daily picks the standard-time 01:30 (EST), got " + fallCal.getTime());
    }

    private static void scheduleSummaryTests() {
        // Start/boot types.
        check("On boot".equals(Schedule.summary(task("On boot", 60, "", "Monday", Schedule.CATCH_UP, 0),
                Schedule.ON_BOOT, false)), "summary on boot");
        check("On Kiosk start".equals(Schedule.summary(task("On Kiosk start", 60, "", "Monday", Schedule.CATCH_UP, 0),
                Schedule.ON_START, false)), "summary on start");

        // Interval wording: minutes under an hour, the hour, whole hours, other spans.
        check("Every 5 min".equals(summaryOf("Interval", 5, "", "Monday")), "summary interval 5");
        check("Every 45 min".equals(summaryOf("Interval", 45, "", "Monday")), "summary interval 45");
        check("Every hour".equals(summaryOf("Interval", 60, "", "Monday")), "summary interval 60");
        check("Every 90 min".equals(summaryOf("Interval", 90, "", "Monday")), "summary interval 90");
        check("Every 2 hours".equals(summaryOf("Interval", 120, "", "Monday")), "summary interval 120");
        check("Every 3 hours".equals(summaryOf("Interval", 180, "", "Monday")), "summary interval 180");

        // Daily times: 12-hour clock, midnight/noon edges, no leading zero.
        check("Daily at 12:00 AM".equals(summaryOf("Daily", 60, "00:00", "Monday")), "summary daily midnight");
        check("Daily at 12:00 PM".equals(summaryOf("Daily", 60, "12:00", "Monday")), "summary daily noon");
        check("Daily at 9:05 AM".equals(summaryOf("Daily", 60, "09:05", "Monday")), "summary daily morning");
        check("Daily at 5:00 PM".equals(summaryOf("Daily", 60, "17:00", "Monday")), "summary daily evening");
        check("Daily (no time set)".equals(summaryOf("Daily", 60, "", "Monday")), "summary daily no time");
        check("Daily (no time set)".equals(summaryOf("Daily", 60, "25:00", "Monday")), "summary daily invalid time");

        // Weekly.
        check("Weekly on Friday at 5:00 PM".equals(summaryOf("Weekly", 60, "17:00", "Friday")), "summary weekly");
        check("Weekly on Friday (no time set)".equals(summaryOf("Weekly", 60, "", "Friday")), "summary weekly no time");

        // Default rows: the summary resolves through the default schedule
        // and says so.
        TaskSpec defaultRow = task("Daily", 60, "17:00", "Monday", Schedule.CATCH_UP, 0);
        check("Daily at 5:00 PM (default)".equals(Schedule.summary(defaultRow, Schedule.DAILY, true)),
                "summary default suffix");
        TaskSpec defaultInterval = task("Interval", 60, "", "Monday", Schedule.CATCH_UP, 0);
        check("Every hour (default)".equals(Schedule.summary(defaultInterval, Schedule.INTERVAL, true)),
                "summary default interval suffix");

        // scheduleSummary is display only: it is parsed from the row, and
        // changing it changes neither the task hash nor the schedule key.
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("executable", "/system/bin/id");
        row.put("args", "");
        row.put("schedule", "Daily");
        row.put("timeOfDay", "07:05");
        row.put("scheduleSummary", "Daily at 7:05 AM");
        TaskSpec withSummary = TaskSpec.fromRow(row);
        check("Daily at 7:05 AM".equals(withSummary.scheduleSummary), "scheduleSummary parses");
        row.put("scheduleSummary", "something else entirely");
        TaskSpec otherSummary = TaskSpec.fromRow(row);
        check(withSummary.hash().equals(otherSummary.hash()), "scheduleSummary does not change the hash");
        check(withSummary.scheduleKey(Schedule.DAILY).equals(otherSummary.scheduleKey(Schedule.DAILY)),
                "scheduleSummary does not change the schedule key");
    }

    private static String summaryOf(String schedule, int interval, String timeOfDay, String weekday) {
        TaskSpec spec = task(schedule, interval, timeOfDay, weekday, Schedule.CATCH_UP, 0);
        return Schedule.summary(spec, schedule, false);
    }

    private static TaskSpec task(String schedule, int interval, String timeOfDay, String weekday, String missedPolicy, int jitter) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("label", "Task");
        row.put("executable", "/system/bin/id");
        row.put("args", "");
        row.put("schedule", schedule);
        row.put("intervalMinutes", Integer.valueOf(interval));
        row.put("timeOfDay", timeOfDay);
        row.put("weekday", weekday);
        row.put("missedPolicy", missedPolicy);
        row.put("jitterMinutes", Integer.valueOf(jitter));
        return TaskSpec.fromRow(row);
    }

    /** Epoch millis for a local date-time in a time zone, via Calendar. */
    private static long at(TimeZone tz, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(tz);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("TaskSchedulerTest failed: " + message);
    }
}

// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One row of the plugin's tasks list setting, parsed defensively. */
public final class TaskSpec {
    public final String label;
    public final boolean enabled;
    public final String executable;
    public final String args;
    public final String backend; // Default, Auto, Root or Shizuku
    public final String schedule; // Default, On boot, On Kiosk start, Interval, Daily or Weekly
    public final int intervalMinutes; // Interval only, 5..10080
    public final String timeOfDay; // Daily/Weekly only, "HH:MM" or "" when invalid
    public final String weekday; // Weekly only, Monday..Sunday
    public final String missedPolicy; // Catch up once or Skip
    public final int jitterMinutes; // 0..120
    public final int timeoutSeconds;
    public final boolean run; // One-shot per-row run toggle; see TaskSchedulerPlugin. Not part of argv or hash.

    public TaskSpec(String label, boolean enabled, String executable, String args,
                    String backend, String schedule, int intervalMinutes, String timeOfDay,
                    String weekday, String missedPolicy, int jitterMinutes,
                    int timeoutSeconds, boolean run) {
        this.label = label;
        this.enabled = enabled;
        this.executable = executable;
        this.args = args;
        this.backend = backend;
        this.schedule = schedule;
        this.intervalMinutes = intervalMinutes;
        this.timeOfDay = timeOfDay;
        this.weekday = weekday;
        this.missedPolicy = missedPolicy;
        this.jitterMinutes = jitterMinutes;
        this.timeoutSeconds = timeoutSeconds;
        this.run = run;
    }

    public static TaskSpec fromRow(Map<String, Object> row) {
        String label = string(row, "label", "Unnamed task");
        boolean enabled = !Boolean.FALSE.equals(row.get("enabled"));
        boolean run = Boolean.TRUE.equals(row.get("run"));
        String executable = string(row, "executable", "");
        String args = string(row, "args", "");
        String backend = string(row, "backend", "Default");
        if (!"Auto".equals(backend) && !"Root".equals(backend) && !"Shizuku".equals(backend)) backend = "Default";
        String schedule = string(row, "schedule", "Default");
        if (!Schedule.ON_BOOT.equals(schedule) && !Schedule.ON_START.equals(schedule)
                && !Schedule.INTERVAL.equals(schedule) && !Schedule.DAILY.equals(schedule)
                && !Schedule.WEEKLY.equals(schedule)) schedule = "Default";
        int interval = number(row, "intervalMinutes", 60);
        if (interval < Schedule.MIN_INTERVAL_MINUTES) interval = Schedule.MIN_INTERVAL_MINUTES;
        if (interval > Schedule.MAX_INTERVAL_MINUTES) interval = Schedule.MAX_INTERVAL_MINUTES;
        String timeOfDay = string(row, "timeOfDay", "");
        if (Schedule.parseTimeOfDayMinutes(timeOfDay) < 0) timeOfDay = "";
        String weekday = string(row, "weekday", "Monday");
        if (Schedule.weekdayConstant(weekday) < 0) weekday = "Monday";
        String missedPolicy = string(row, "missedPolicy", Schedule.CATCH_UP);
        if (!Schedule.SKIP.equals(missedPolicy)) missedPolicy = Schedule.CATCH_UP;
        int jitter = number(row, "jitterMinutes", 0);
        if (jitter < 0) jitter = 0;
        if (jitter > 120) jitter = 120;
        int timeout = number(row, "timeoutSeconds", 10);
        if (timeout < 1) timeout = 1;
        if (timeout > 30) timeout = 30;
        return new TaskSpec(label, enabled, executable, args, backend, schedule, interval,
                timeOfDay, weekday, missedPolicy, jitter, timeout, run);
    }

    /** Executable plus parsed arguments, as one argv list. */
    public List<String> argv() {
        List<String> argv = new ArrayList<String>();
        argv.add(executable);
        argv.addAll(ArgParser.split(args));
        return argv;
    }

    /** Identity hash: the command itself. Editing the command reruns boot tasks. */
    public String hash() { return SchedulerState.hash(executable, args); }

    /**
     * Schedule key: the command plus the schedule fields that decide when
     * it is due (resolved schedule, interval, time, weekday). Anchors in
     * SchedulerState are stored under this key, so editing one of those
     * fields gives the task a fresh key and therefore a fresh anchor,
     * while edits to other fields (backend, timeout, jitter, missed
     * policy) keep the existing anchor.
     */
    public String scheduleKey(String effectiveSchedule) {
        return SchedulerState.hash(executable, args + "\u0000" + effectiveSchedule
                + "|" + intervalMinutes + "|" + timeOfDay + "|" + weekday);
    }

    static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.length() == 0 && fallback != null && !"args".equals(key) ? fallback : text;
    }

    static int number(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return (int) Math.round(Double.parseDouble((String) value)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }
}

// SPDX-License-Identifier: Apache-2.0
package taskscheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time migration of pre-1.4 settings, as a pure function over
 * the settings map so it can be unit-tested on a host JVM.
 *
 * <p>Versions before 1.4 had three plugin-level settings —
 * {@code defaultBackend}, {@code defaultSchedule} and
 * {@code startupDelaySeconds} — and a task row could store the
 * schedule or backend {@code Default}, meaning "use the plugin-level
 * value". Those settings are gone in 1.4; each task carries its own
 * schedule and backend, plus a per-task delay. When the configure
 * flow sees legacy markers — any of the three old keys still
 * present, or any row still storing {@code Default} — it resolves
 * every row through here:
 *
 * <ul>
 * <li>a {@code Default} schedule takes the old {@code defaultSchedule}
 * when that is a real schedule, else {@code On boot}; a
 * {@code Default} backend likewise takes the old
 * {@code defaultBackend}, else {@code Auto}. (The old globals could
 * not be {@code Manual}, so that value is not accepted from them.)
 * <li>a row with no stored {@code delaySeconds} takes the old
 * {@code startupDelaySeconds} (clamped to the delay range) when its
 * resolved schedule is {@code On boot} or {@code On Kiosk start} —
 * the two schedules the startup delay used to hold back — and 0
 * otherwise.
 * </ul>
 *
 * <p>The plugin uses the resolved rows in memory whether or not the
 * write-back lands, and writes the list back only when {@link
 * Result#changed} is set. After a successful write-back no row
 * stores {@code Default} and every row carries a delay, so resolving
 * the migrated settings again reports no change — the migration
 * runs at most once in effect, even if the old plugin-level keys
 * linger in the settings map.
 */
public final class LegacyMigration {
    private LegacyMigration() {}

    public static final class Result {
        /** The task rows to parse: copies of the stored rows, resolved. */
        public final List<Map<String, Object>> rows;
        /** True when resolution altered any row and the list should be written back. */
        public final boolean changed;

        Result(List<Map<String, Object>> rows, boolean changed) {
            this.rows = rows;
            this.changed = changed;
        }
    }

    public static Result resolveRows(Map<String, Object> settings) {
        List<Map<String, Object>> stored = new ArrayList<Map<String, Object>>();
        Object raw = settings.get("tasks");
        if (raw instanceof List) {
            for (Object row : (List<?>) raw) {
                if (row instanceof Map) stored.add((Map<String, Object>) row);
            }
        }
        boolean legacyKeys = settings.containsKey("defaultBackend")
                || settings.containsKey("defaultSchedule")
                || settings.containsKey("startupDelaySeconds");
        boolean rowMarker = false;
        for (int i = 0; i < stored.size() && !rowMarker; i++) {
            rowMarker = "Default".equals(text(stored.get(i).get("schedule")))
                    || "Default".equals(text(stored.get(i).get("backend")));
        }
        if (!legacyKeys && !rowMarker) return new Result(stored, false);

        String legacySchedule = validLegacySchedule(text(settings.get("defaultSchedule")));
        String legacyBackend = validLegacyBackend(text(settings.get("defaultBackend")));
        int legacyDelay = TaskSpec.number(settings, "startupDelaySeconds", 0);
        if (legacyDelay < 0) legacyDelay = 0;
        if (legacyDelay > TaskSpec.MAX_DELAY_SECONDS) legacyDelay = TaskSpec.MAX_DELAY_SECONDS;

        boolean changed = false;
        List<Map<String, Object>> resolved = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < stored.size(); i++) {
            Map<String, Object> row = new HashMap<String, Object>(stored.get(i));
            String schedule = text(row.get("schedule"));
            String resolvedSchedule;
            if ("Default".equals(schedule)) {
                resolvedSchedule = legacySchedule != null ? legacySchedule : Schedule.ON_BOOT;
                row.put("schedule", resolvedSchedule);
                changed = true;
            } else if (isSchedule(schedule)) {
                resolvedSchedule = schedule;
            } else {
                // Absent or unrecognised: TaskSpec falls back to On boot,
                // and the delay below follows that resolved value.
                resolvedSchedule = Schedule.ON_BOOT;
            }
            if ("Default".equals(text(row.get("backend")))) {
                row.put("backend", legacyBackend != null ? legacyBackend : "Auto");
                changed = true;
            }
            if (row.get("delaySeconds") == null) {
                int delay = (Schedule.ON_BOOT.equals(resolvedSchedule) || Schedule.ON_START.equals(resolvedSchedule))
                        ? legacyDelay : 0;
                row.put("delaySeconds", Integer.valueOf(delay));
                changed = true;
            }
            resolved.add(row);
        }
        return new Result(resolved, changed);
    }

    private static boolean isSchedule(String value) {
        return Schedule.ON_BOOT.equals(value) || Schedule.ON_START.equals(value)
                || Schedule.INTERVAL.equals(value) || Schedule.DAILY.equals(value)
                || Schedule.WEEKLY.equals(value) || Schedule.MANUAL.equals(value);
    }

    /** The old defaultSchedule, or null when it is not one of the schedules it could hold. */
    private static String validLegacySchedule(String value) {
        return (Schedule.ON_BOOT.equals(value) || Schedule.ON_START.equals(value)
                || Schedule.INTERVAL.equals(value) || Schedule.DAILY.equals(value)
                || Schedule.WEEKLY.equals(value)) ? value : null;
    }

    private static String validLegacyBackend(String value) {
        return ("Auto".equals(value) || "Root".equals(value) || "Shizuku".equals(value)) ? value : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

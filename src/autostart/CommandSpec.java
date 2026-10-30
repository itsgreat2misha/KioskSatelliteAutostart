// SPDX-License-Identifier: Apache-2.0
package autostart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One row of the plugin's commands list setting, parsed defensively. */
public final class CommandSpec {
    public final String label;
    public final boolean enabled;
    public final String executable;
    public final String args;
    public final String backend; // Default, Auto, Root or Shizuku
    public final String runMode; // Default, Once per boot or Every start
    public final int timeoutSeconds;
    public final boolean run; // One-shot per-row run toggle; see AutostartPlugin. Not part of argv or hash.

    public CommandSpec(String label, boolean enabled, String executable, String args,
                       String backend, String runMode, int timeoutSeconds) {
        this(label, enabled, executable, args, backend, runMode, timeoutSeconds, false);
    }

    public CommandSpec(String label, boolean enabled, String executable, String args,
                       String backend, String runMode, int timeoutSeconds, boolean run) {
        this.label = label;
        this.enabled = enabled;
        this.executable = executable;
        this.args = args;
        this.backend = backend;
        this.runMode = runMode;
        this.timeoutSeconds = timeoutSeconds;
        this.run = run;
    }

    public static CommandSpec fromRow(Map<String, Object> row) {
        String label = string(row, "label", "Unnamed command");
        boolean enabled = !Boolean.FALSE.equals(row.get("enabled"));
        boolean run = Boolean.TRUE.equals(row.get("run"));
        String executable = string(row, "executable", "");
        String args = string(row, "args", "");
        String backend = string(row, "backend", "Default");
        if (!"Auto".equals(backend) && !"Root".equals(backend) && !"Shizuku".equals(backend)) backend = "Default";
        String runMode = string(row, "runMode", "Default");
        if (!"Once per boot".equals(runMode) && !"Every start".equals(runMode)) runMode = "Default";
        int timeout = number(row, "timeoutSeconds", 10);
        if (timeout < 1) timeout = 1;
        if (timeout > 30) timeout = 30;
        return new CommandSpec(label, enabled, executable, args, backend, runMode, timeout, run);
    }

    /** Executable plus parsed arguments, as one argv list. */
    public List<String> argv() {
        List<String> argv = new ArrayList<String>();
        argv.add(executable);
        argv.addAll(ArgParser.split(args));
        return argv;
    }

    public String hash() { return RunState.hash(executable, args); }

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

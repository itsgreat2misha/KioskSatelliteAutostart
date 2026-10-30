// SPDX-License-Identifier: Apache-2.0
package autostart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Autostart: run user-chosen commands when Kiosk Satellite starts.
 *
 * <p>Design, in one place:
 *
 * <ul>
 * <li>Callbacks ({@code start}, {@code configure}, {@code execute},
 * {@code onEvent}) return within the SDK's three-second deadline. All
 * real work runs on one daemon worker thread, sequentially, so commands
 * never overlap and a long command cannot stall Kiosk.
 * <li>One command is one executable plus arguments, stored that way so
 * the same command can run through either backend. The root backend
 * runs them through su, trying {@code su 0 <argv...>} first and
 * {@code su -c <shell string>} second (see {@link RootRunner}); the
 * Shizuku backend passes them verbatim to {@code host.executeShizuku},
 * which takes an absolute executable and separate arguments and does
 * no shell joining. Use {@code /system/bin/sh} with {@code -c} as the
 * executable when shell syntax is genuinely needed.
 * <li>Shizuku rules are honoured: at most one pending request, at
 * least 250 ms between requests (300 ms here), timeouts clamped to
 * 100-30000 ms, at most 32 argv elements, 4096 characters per argument
 * and 16384 total, output capped at 32768 bytes per stream. The
 * Shizuku callback only stores its result and counts a latch down;
 * it never blocks and never calls the host, so it cannot miss the
 * callback deadline. The worker waits for the latch.
 * <li>Once-per-boot tracking uses the kernel boot id (see
 * {@link BootId}) and the {@code runState} setting (see
 * {@link RunState}). Only successful automatic runs are marked, so a
 * failed command retries on the next Kiosk start, and a manual run
 * never marks anything.
 * <li>Failures never stop later commands and never throw out of a
 * callback. Every result is logged, summarised in the plugin status
 * and published as text sensors.
 * </ul>
 */
public final class AutostartPlugin implements KioskPlugin {
    private static final long SHIZUKU_GAP_MS = 300;

    private volatile PluginHost host;
    private volatile boolean stopped;
    private volatile Config config = Config.empty();
    private volatile String runStateRaw = "";
    private volatile boolean needsShizukuRetry;
    private volatile String currentSetupRun;
    private volatile boolean setupCancelled;

    private ExecutorService worker;

    private static final class Config {
        final String defaultBackend;
        final String defaultRunMode;
        final int startupDelaySeconds;
        final List<CommandSpec> commands;
        final String singleCommand;
        final String sessionPid;

        Config(String defaultBackend, String defaultRunMode, int startupDelaySeconds, List<CommandSpec> commands, String singleCommand, String sessionPid) {
            this.defaultBackend = defaultBackend;
            this.defaultRunMode = defaultRunMode;
            this.startupDelaySeconds = startupDelaySeconds;
            this.commands = commands;
            this.singleCommand = singleCommand;
            this.sessionPid = sessionPid;
        }

        static Config empty() {
            return new Config("Auto", "Once per boot", 0, Collections.<CommandSpec>emptyList(), "", "");
        }

        static Config parse(Map<String, Object> settings) {
            String backend = CommandSpec.string(settings, "defaultBackend", "Auto");
            if (!"Root".equals(backend) && !"Shizuku".equals(backend)) backend = "Auto";
            String runMode = CommandSpec.string(settings, "defaultRunMode", "Once per boot");
            if (!"Every start".equals(runMode)) runMode = "Once per boot";
            int delay = CommandSpec.number(settings, "startupDelaySeconds", 0);
            if (delay < 0) delay = 0;
            if (delay > 60) delay = 60;
            List<CommandSpec> commands = new ArrayList<CommandSpec>();
            Object raw = settings.get("commands");
            if (raw instanceof List) {
                for (Object row : (List<?>) raw) {
                    if (row instanceof Map) {
                        CommandSpec spec = CommandSpec.fromRow((Map<String, Object>) row);
                        if (spec.executable != null && spec.executable.length() > 0) commands.add(spec);
                    }
                }
            }
            String single = CommandSpec.string(settings, "singleCommand", "");
            String pid = CommandSpec.string(settings, "sessionPid", "");
            return new Config(backend, runMode, delay, commands, single, pid);
        }
    }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.stopped = false;
        configure(settings);
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "kiosk-autostart");
                thread.setDaemon(true);
                return thread;
            }
        });
        publishSensors("Autostart ready.", "No run yet.");
        // Automatic runs must happen on a Kiosk app start, not on a
        // plugin session restart inside the same Kiosk process. KS
        // calls start() for both: explicit enable, master-switch on,
        // and a plugin update all restart the session in the same
        // process, and saving settings does not call start() at all.
        // So the run you saw after adding commands was the enable
        // start, not the settings save. Distinguish the two with the
        // Kiosk process id, persisted in the hidden sessionPid
        // setting: a first start (never started before) only arms
        // the plugin, a start in the same process as the last start
        // is a session restart and does not run, and a start in a
        // new process is an app start and does run. If the pid
        // cannot be determined, fall back to the old behaviour and
        // run, so a boot is never silently skipped for that reason.
        // Known limit, stated honestly: disabling, rebooting, and
        // then enabling again looks exactly like an app start with
        // a pre-enabled plugin (new process, stored pid differs),
        // so that one sequence still runs on enable.
        Integer pid = Pid.current();
        String pidStr = pid == null ? "" : String.valueOf(pid.intValue());
        boolean firstStart = config.sessionPid == null || config.sessionPid.length() == 0;
        boolean sameProcess = !firstStart && pidStr.length() > 0 && pidStr.equals(config.sessionPid);
        boolean runAutomatic = !firstStart && !sameProcess;
        if (pidStr.length() > 0 && !pidStr.equals(config.sessionPid)) {
            try {
                Map<String, Object> values = new HashMap<String, Object>();
                values.put("sessionPid", pidStr);
                host.updateSettings(values);
            } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
        }
        if (runAutomatic) {
            statusSafe("Autostart ready.", false);
            scheduleAutomatic();
        } else {
            statusSafe("Autostart armed. Commands will run on the next Kiosk start, not on enable or a plugin restart.", false);
        }
    }

    @Override
    public void configure(Map<String, Object> settings) {
        config = Config.parse(settings);
        String raw = CommandSpec.string(settings, "runState", "");
        runStateRaw = raw;
        publishChoices();
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("runNow".equals(command)) scheduleManual(null);
        else if ("runSelected".equals(command)) scheduleSingle(null);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if ("shizuku.state".equals(event)) {
            if (Boolean.TRUE.equals(payload.get("granted")) && needsShizukuRetry) {
                needsShizukuRetry = false;
                scheduleAutomatic();
            }
            return;
        }
        if ("settings.button".equals(event) && payload != null) {
            String id = String.valueOf(payload.get("id"));
            String run = payload.get("run") == null ? null : String.valueOf(payload.get("run"));
            if ("runNow".equals(id)) {
                scheduleManual(run);
            } else if ("runSelected".equals(id)) {
                scheduleSingle(run);
            } else if ("clearRunState".equals(id)) {
                scheduleClearRunState(run);
            } else if ("probeBackends".equals(id)) {
                probeBackends(run);
            }
            return;
        }
        if ("settings.cancel".equals(event) && payload != null) {
            String run = payload.get("run") == null ? null : String.valueOf(payload.get("run"));
            if (run != null && run.equals(currentSetupRun)) setupCancelled = true;
        }
    }

    @Override
    public void stop() {
        stopped = true;
        ExecutorService executor = worker;
        worker = null;
        host = null;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    private void scheduleAutomatic() {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runPass(false, null); }
        });
    }

    private void scheduleManual(final String setupRun) {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runPass(true, setupRun); }
        });
    }

    private void scheduleSingle(final String setupRun) {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { runSinglePass(setupRun); }
        });
    }

    private void scheduleClearRunState(final String setupRun) {
        final ExecutorService executor = worker;
        if (executor == null || stopped) return;
        executor.submit(new Runnable() {
            public void run() { clearRunStatePass(setupRun); }
        });
    }

    /** Publish the command list as choices for the Run one command setting. */
    private void publishChoices() {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try {
            List<CommandSpec> commands = config.commands;
            String[] ids = new String[commands.size()];
            String[] labels = new String[commands.size()];
            for (int i = 0; i < commands.size(); i++) {
                ids[i] = commands.get(i).hash();
                labels[i] = commands.get(i).label;
            }
            snapshotHost.setSettingChoices("singleCommand", ids, labels);
        } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private void clearRunStatePass(String setupRun) {
        if (setupRun != null) {
            currentSetupRun = setupRun;
            setupCancelled = false;
        }
        try {
            runStateRaw = "";
            PluginHost snapshotHost = safeHost();
            if (snapshotHost != null) {
                Map<String, Object> values = new HashMap<String, Object>();
                values.put("runState", "");
                snapshotHost.updateSettings(values);
            }
            String message = "Run state cleared. Once per boot commands will run again on the next Kiosk start.";
            statusSafe(message, false);
            logSafe(message);
            reportSetup(setupRun, "success", message);
        } catch (RuntimeException e) {
            logSafe("Could not clear run state: " + e.getMessage());
            reportSetup(setupRun, "error", "Could not clear run state: " + e.getMessage());
        } catch (LinkageError e) {
            reportSetup(setupRun, "error", "This Kiosk Satellite version cannot clear run state.");
        } finally {
            if (setupRun != null) currentSetupRun = null;
        }
    }

    /** Run only the command picked in the Run one command setting. Manual: ignores run mode, never marks run state. */
    private void runSinglePass(String setupRun) {
        Config snapshot = config;
        if (setupRun != null) {
            currentSetupRun = setupRun;
            setupCancelled = false;
        }
        try {
            CommandSpec found = null;
            for (int i = 0; i < snapshot.commands.size(); i++) {
                if (snapshot.commands.get(i).hash().equals(snapshot.singleCommand)) { found = snapshot.commands.get(i); break; }
            }
            if (found == null) {
                String message = snapshot.singleCommand.length() == 0
                        ? "No command selected. Pick one in Run one command first."
                        : "Selected command no longer exists. Pick one in Run one command again.";
                statusSafe(message, true);
                logSafe(message);
                reportSetup(setupRun, "error", message);
                return;
            }
            if (!found.enabled) {
                String message = found.label + ": disabled, not run.";
                statusSafe(message, true);
                logSafe(message);
                reportSetup(setupRun, "error", message);
                return;
            }
            if (stopped || setupCancelled) return;
            BackendChoice choice = chooseBackend(found, snapshot, null);
            if (choice.backend == null) {
                String message = found.label + ": failed (" + choice.failure + ")";
                statusSafe(message, true);
                logSafe(message);
                reportSetup(setupRun, "error", message);
                return;
            }
            Result result;
            if ("Root".equals(choice.backend)) {
                result = RootRunner.run(found.argv(), found.timeoutSeconds);
            } else {
                result = runShizuku(found);
            }
            if (stopped) return;
            String line = result.succeeded()
                    ? found.label + ": ok"
                    : found.label + ": failed (" + result.summary() + ")";
            publishSensors("Last run: " + line, line);
            statusSafe(line, !result.succeeded());
            logSafe(line + (result.succeeded() ? "" : outputSuffix(result)));
            reportSetup(setupRun, result.succeeded() ? "success" : "error", line);
        } catch (RuntimeException e) {
            logSafe("Run selected failed: " + e.getMessage());
            statusSafe("Run selected failed: " + e.getMessage(), true);
            reportSetup(setupRun, "error", "Run selected failed: " + e.getMessage());
        } finally {
            if (setupRun != null) currentSetupRun = null;
        }
    }

    private void runPass(boolean manual, String setupRun) {
        Config snapshot = config;
        if (setupRun != null) {
            currentSetupRun = setupRun;
            setupCancelled = false;
        }
        try {
            if (!manual && snapshot.startupDelaySeconds > 0) {
                try { Thread.sleep(snapshot.startupDelaySeconds * 1000L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (stopped) return;
                snapshot = config;
            }
            List<CommandSpec> commands = snapshot.commands;
            if (commands.isEmpty()) {
                String message = "No commands configured.";
                publishSensors(message, "No run yet.");
                statusSafe(message, false);
                reportSetup(setupRun, "success", message);
                return;
            }
            String bootId = BootId.current();
            RunState stored = RunState.parse(runStateRaw);
            boolean bootKnown = bootId != null;
            RunState working = (bootKnown && bootId.equals(stored.bootId())) ? stored : new RunState(null, Collections.<String>emptySet());
            boolean workingChanged = false;
            boolean shizukuFailure = false;
            int ok = 0, skipped = 0, failed = 0;
            List<String> lines = new ArrayList<String>();
            Boolean rootInPass = null;
            long lastShizukuMs = 0;
            for (int i = 0; i < commands.size(); i++) {
                if (stopped || setupCancelled) break;
                CommandSpec spec = commands.get(i);
                if (!spec.enabled) continue;
                String runMode = "Default".equals(spec.runMode) ? snapshot.defaultRunMode : spec.runMode;
                boolean oncePerBoot = "Once per boot".equals(runMode);
                if (!manual && oncePerBoot && bootKnown && working.isCompleted(spec.hash())) {
                    skipped++;
                    lines.add(spec.label + ": skipped (already ran this boot)");
                    continue;
                }
                BackendChoice choice = chooseBackend(spec, snapshot, rootInPass);
                if (choice.rootInPass != null) rootInPass = choice.rootInPass;
                if (choice.backend == null) {
                    failed++;
                    lines.add(spec.label + ": failed (" + choice.failure + ")");
                    logSafe(spec.label + ": " + choice.failure);
                    if (choice.failure != null && choice.failure.contains("Shizuku")) shizukuFailure = true;
                    if (!reportSetup(setupRun, "running", spec.label + ": failed")) break;
                    continue;
                }
                if ("Shizuku".equals(choice.backend)) {
                    long now = System.currentTimeMillis();
                    long wait = SHIZUKU_GAP_MS - (now - lastShizukuMs);
                    if (lastShizukuMs != 0 && wait > 0) {
                        try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }
                Result result;
                if ("Root".equals(choice.backend)) {
                    result = RootRunner.run(spec.argv(), spec.timeoutSeconds);
                } else {
                    lastShizukuMs = System.currentTimeMillis();
                    result = runShizuku(spec);
                }
                if (stopped) break;
                if (result.succeeded()) {
                    ok++;
                    lines.add(spec.label + ": ok");
                    logSafe(spec.label + ": ok");
                    if (!manual && oncePerBoot && bootKnown) {
                        working = working.withCompleted(spec.hash());
                        workingChanged = true;
                    }
                } else {
                    failed++;
                    lines.add(spec.label + ": failed (" + result.summary() + ")");
                    logSafe(spec.label + ": failed (" + result.summary() + ")" + outputSuffix(result));
                    if ("Shizuku".equals(choice.backend)) shizukuFailure = true;
                }
                if (!reportSetup(setupRun, "running", lines.get(lines.size() - 1))) break;
            }
            needsShizukuRetry = shizukuFailure && !manual;
            String summary = "Last run: " + ok + " ok, " + skipped + " skipped, " + failed + " failed.";
            String detail = joinLines(lines);
            publishSensors(summary, detail);
            statusSafe(summary, failed > 0);
            if (workingChanged && bootKnown) {
                String encoded = working.encode(bootId);
                if (encoded != null) {
                    runStateRaw = encoded;
                    try {
                        PluginHost snapshotHost = safeHost();
                        if (snapshotHost != null) {
                            Map<String, Object> values = new HashMap<String, Object>();
                            values.put("runState", encoded);
                            snapshotHost.updateSettings(values);
                        }
                    } catch (RuntimeException e) {
                        logSafe("Could not persist run state: " + e.getMessage());
                    } catch (LinkageError e) {
                        logSafe("This Kiosk Satellite version cannot persist run state; Once per boot commands will run again on the next start.");
                    }
                } else {
                    logSafe("Run state would exceed the setting limit; Once per boot commands will run again on the next start.");
                }
            }
            reportSetup(setupRun, failed > 0 ? "error" : "success", summary);
        } catch (RuntimeException e) {
            logSafe("Autostart run failed: " + e.getMessage());
            statusSafe("Autostart run failed: " + e.getMessage(), true);
            reportSetup(setupRun, "error", "Autostart run failed: " + e.getMessage());
        } finally {
            if (setupRun != null) currentSetupRun = null;
        }
    }

    private static final class BackendChoice {
        final String backend;
        final String failure;
        final Boolean rootInPass;
        BackendChoice(String backend, String failure, Boolean rootInPass) {
            this.backend = backend; this.failure = failure; this.rootInPass = rootInPass;
        }
    }

    private BackendChoice chooseBackend(CommandSpec spec, Config snapshot, Boolean rootInPass) {
        String wanted = "Default".equals(spec.backend) ? snapshot.defaultBackend : spec.backend;
        if ("Root".equals(wanted)) return new BackendChoice("Root", null, rootInPass);
        if ("Shizuku".equals(wanted)) {
            String state = shizukuFailureReason();
            if (state != null) return new BackendChoice(null, state, rootInPass);
            return new BackendChoice("Shizuku", null, rootInPass);
        }
        // Auto: root first, Shizuku second.
        if (rootInPass == null) {
            rootInPass = RootRunner.probe();
        }
        if (Boolean.TRUE.equals(rootInPass)) return new BackendChoice("Root", null, rootInPass);
        String state = shizukuFailureReason();
        if (state != null) return new BackendChoice(null, "No backend available (root unavailable; Shizuku: " + state + ")", rootInPass);
        return new BackendChoice("Shizuku", null, rootInPass);
    }

    private String shizukuFailureReason() {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return "plugin stopped";
        try {
            Map<String, Object> state = snapshotHost.shizukuState();
            if (state == null) return "Shizuku state is unavailable";
            if (!Boolean.TRUE.equals(state.get("granted"))) {
                Object status = state.get("status");
                return "Shizuku is not ready (" + (status == null ? "unknown" : String.valueOf(status)) + ")";
            }
            return null;
        } catch (RuntimeException e) {
            return "Shizuku is unavailable (" + message(e) + ")";
        } catch (LinkageError e) {
            return "Shizuku is unavailable on this Kiosk Satellite version";
        }
    }

    private Result runShizuku(CommandSpec spec) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return Result.failure("Plugin stopped");
        List<String> argv = spec.argv();
        if (spec.executable == null || !spec.executable.startsWith("/")) {
            return Result.failure("Shizuku needs an absolute executable path, got: " + spec.executable);
        }
        if (argv.size() > 32) return Result.failure("Shizuku allows at most 32 arguments, got " + argv.size());
        int total = 0;
        for (int i = 0; i < argv.size(); i++) {
            if (argv.get(i).length() > 4096) return Result.failure("Shizuku allows at most 4096 characters per argument");
            total += argv.get(i).length();
        }
        if (total > 16384) return Result.failure("Shizuku allows at most 16384 characters in total");
        int timeoutMs = spec.timeoutSeconds * 1000;
        if (timeoutMs < 100) timeoutMs = 100;
        if (timeoutMs > 30000) timeoutMs = 30000;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        final Object[] data = new Object[1];
        final String[] error = new String[1];
        try {
            snapshotHost.executeShizuku(argv.toArray(new String[0]), timeoutMs, new PluginHost.CommandCallback() {
                public void onResult(boolean resultOk, Object resultData, String resultError) {
                    ok[0] = resultOk;
                    data[0] = resultData;
                    error[0] = resultError;
                    latch.countDown();
                }
            });
        } catch (RuntimeException e) {
            return Result.failure("Shizuku request failed: " + message(e));
        } catch (LinkageError e) {
            return Result.failure("Shizuku is unavailable on this Kiosk Satellite version");
        }
        try {
            boolean arrived = latch.await(timeoutMs + 10000L, TimeUnit.MILLISECONDS);
            if (!arrived) return Result.failure("Shizuku callback did not arrive");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("Shizuku command was interrupted");
        }
        if (!ok[0]) return Result.failure(error[0] == null ? "Shizuku request failed." : error[0]);
        if (!(data[0] instanceof Map)) return Result.failure("Shizuku returned no result");
        Map<?, ?> result = (Map<?, ?>) data[0];
        boolean timedOut = Boolean.TRUE.equals(result.get("timedOut"));
        boolean truncated = Boolean.TRUE.equals(result.get("truncated"));
        int exitCode = -1;
        if (result.get("exitCode") instanceof Number) exitCode = ((Number) result.get("exitCode")).intValue();
        String stdout = result.get("stdout") == null ? "" : String.valueOf(result.get("stdout"));
        String stderr = result.get("stderr") == null ? "" : String.valueOf(result.get("stderr"));
        String output = stdout;
        if (stderr.length() > 0) output += "\n[stderr] " + stderr;
        if (output.length() > RootRunner.OUTPUT_CAP) {
            output = output.substring(0, RootRunner.OUTPUT_CAP);
            truncated = true;
        }
        return new Result(exitCode, timedOut, truncated, output, null);
    }

    private void probeBackends(final String setupRun) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                boolean root = RootRunner.probe();
                String shizuku = shizukuFailureReason();
                String message = "Root (su): " + (root ? "available (uid 0 via " + RootRunner.formLabel() + ")" : "unavailable (neither su 0 nor su -c reported uid 0)")
                        + ". Shizuku: " + (shizuku == null ? "ready" : shizuku) + ".";
                statusSafe(message, false);
                logSafe(message);
                reportSetup(setupRun, "success", message);
            }
        }, "kiosk-autostart-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean reportSetup(String setupRun, String state, String message) {
        if (setupRun == null) return true;
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return false;
        try {
            return snapshotHost.setupResult(setupRun, state, truncate(message, 1000));
        } catch (RuntimeException e) {
            return true;
        } catch (LinkageError e) {
            return true;
        }
    }

    private PluginHost safeHost() {
        if (stopped) return null;
        return host;
    }

    private void logSafe(String message) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try { snapshotHost.log(truncate(message, 1000)); } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private void statusSafe(String message, boolean error) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try { snapshotHost.status(truncate(message, 1000), error); } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private void publishSensors(String status, String lastRun) {
        PluginHost snapshotHost = safeHost();
        if (snapshotHost == null) return;
        try {
            snapshotHost.publishTextSensor("status", "Autostart status", truncate(status, 512));
            snapshotHost.publishTextSensor("last_run", "Autostart last run", truncate(lastRun, 512));
        } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(" | ");
            out.append(lines.get(i));
        }
        return out.toString();
    }

    private static String outputSuffix(Result result) {
        if (result.output == null || result.output.length() == 0) return "";
        String text = result.output.trim();
        if (text.length() == 0) return "";
        return " Output: " + truncate(text, 200);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null ? e.toString() : text;
    }
}

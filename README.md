# Autostart for Kiosk Satellite

Run commands you choose every time Kiosk Satellite starts, through root
(`su`) or Shizuku. A command can run on every start, or only once per
boot, so a Kiosk restart does not repeat it.

Autostart is a generic plugin, not tied to any one app. Examples: re-apply
a setting that a device drops on reboot, launch an app or service, or
grant a permission after Kiosk starts.

## Installation

### Community Plugins (when listed)

Search for `Autostart` in **Settings > Plugin Manager > Browse plugins**
in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on
   **Enable Plugins**.
2. Choose **Add plugin**, paste
   `https://github.com/itsgreat2misha/KioskSatelliteAutostart` and choose
   **Preview**.
3. Review the plugin and choose **Trust and install**.
4. Enable **Autostart** from its entry row, then open its settings and
   add commands.

For local testing only, **Plugin Manager > Developer Tools > Install
from ZIP** accepts the ZIP built by `python3 tools/build.py`.

## Settings

- **Default backend** — `Auto` uses root when `su` works, otherwise
  Shizuku. A command can override it with `Root` or `Shizuku`.
- **Default run mode** — `Once per boot` skips a command on later
  Kiosk restarts in the same boot; `Every start` runs it every time.
  A command can override it.
- **Startup delay** — seconds to wait after Kiosk starts before
  running automatic commands. Manual runs ignore it.
- **Commands** — a list. Each row has a label, an enabled toggle, an
  executable (absolute path, for example `/system/bin/settings`), its
  arguments (one string, split quote-aware, for example
  `put system user_rotation 1`), a backend, a run mode and a timeout
  in seconds (1–30).
- **Enabled** (per row) — turn a command off without deleting it.
  A disabled command stays in the list, is skipped by automatic
  runs and by Run commands now, and a per-row Run toggle on it does
  not run it. Turn it back on to use it again.
- **Run now** (per row) — a one-shot toggle, not a button: the SDK
  has no button field for list rows, so a list row cannot carry its
  own Run button next to the reorder handle (rows show only a title
  and a subtitle there). Open the row, turn Run now on and save:
  the plugin runs only that command, ignoring Once per boot
  tracking, without marking it as run for this boot, then resets
  the toggle to off. A disabled command does not run. The toggle
  only takes effect on a settings save while the plugin is running;
  on a Kiosk start the automatic rules apply instead.
- **Run state and Session process id (managed by Autostart)** —
  hidden settings, not editable fields. The SDK has no read-only or
  hidden setting type, so these are hidden with a condition that
  never matches, and are managed by the plugin itself. Run state
  stores which commands already ran on this boot; clearing it via
  **Clear run state** makes Once per boot commands run again on the
  next Kiosk start. Session process id records the Kiosk process
  id of the last start, so a plugin session restart in the same
  process does not run commands again (see below).

Buttons on the settings page:

- **Run commands now** — runs all enabled commands now, ignoring
  Once per boot tracking. A manual run does not mark commands as run
  for this boot, so testing a command does not consume its once-per-boot
  slot.
- **Clear run state** — clears Once per boot tracking, so those
  commands run again on the next Kiosk start.
- **Check backends** — reports whether root (`su`, uid 0) and Shizuku
  are available on this kiosk.

The one declared action is for assigning to a gesture, the kiosk
drawer or Home Assistant — pressing it in the Actions list does not
run anything there, it only offers it as an assignable action:

- **Run all autostart commands** — the same as Run commands now,
  when triggered from a gesture, drawer shortcut or Home Assistant
  button. There is no per-command declared action: the manifest's
  actions are static, while the command list is user-edited, so a
  list of commands cannot be turned into one action per command.
  Per-command runs use the Run now toggle in the row editor instead.

## How it works

A command is one executable plus arguments, so the same command works
through either backend. The root backend runs them through `su`,
trying `su 0 <argv...>` first and `su -c <shell string>` second,
and remembers which form reported uid 0. The first form is the one
the Tuya F5-3 panel needs: `su -c` fails there with exit 1, which
is why v1.0's Check backends wrongly reported root as unavailable
on that panel even though `su` works. The Shizuku backend passes
them verbatim to Kiosk Satellite's `executeShizuku`, which takes an
absolute executable and separate arguments and does no shell
joining. When shell syntax is genuinely needed, use
`/system/bin/sh` with `-c` as the executable.

Automatic commands run on a Kiosk app start, not on a plugin
session restart. Kiosk Satellite calls `start()` for both explicit
enable and app startup, and saving settings does not call it at
all — so a run seen right after adding commands was the enable
start, not the save. Autostart distinguishes the two with the
Kiosk process id: a first start only arms the plugin, a start in
the same process as the last start is a session restart (enable,
master-switch on, plugin update) and does not run, and a start in
a new process is an app start and does run. One sequence stays
indistinguishable: disabling, rebooting, then enabling again
looks exactly like an app start with a pre-enabled plugin, so
that sequence still runs on enable.

Callbacks return immediately; all commands run sequentially on a
background worker thread, so a long command cannot stall Kiosk and a
crashing command cannot take Kiosk down. Failures do not stop later
commands. Every result is logged, summarised in the plugin status and
published as the `Autostart status` and `Autostart last run` text
sensors.

Once-per-boot tracking is keyed by the kernel boot id
(`/proc/sys/kernel/random/boot_id`, falling back to the kernel boot
time from `/proc/stat`), not by wall-clock time, so a device whose
clock resets on reboot cannot falsely skip commands. Only successful
automatic runs are marked, so a failed command retries on the next
Kiosk start. The record is stored in the plugin's `runState` setting,
the SDK-sanctioned persistence for a plugin, and it stays under the
512-character string limit even with 32 commands because it stores
short hashes of commands, not their text.

Shizuku limits are honoured: at most one pending request, at least
300 ms between requests, 1–32 arguments, 4096 characters per
argument, 16384 total, and output capped at 32768 bytes per stream.
Shizuku must be running and granted to Kiosk Satellite before a
Shizuku command can run; Autostart does not start Shizuku and does
not substitute `su` when a command is set to Shizuku only. If Shizuku
becomes ready later in the same session, unfinished automatic
commands are retried once.

## Security

This plugin runs inside Kiosk Satellite with its privileges, like
every SDK 1 plugin. A command set to Root runs as root, and a command
set to Shizuku runs as Shizuku's shell or root user. Only install
and enable it if you trust the commands you enter and anyone else
who can edit this plugin's settings. The plugin sends no data
anywhere: it runs local commands and reports only to Kiosk
Satellite's log, status and text sensors.

Do not put secrets in commands. Command executables and arguments
are stored as plain plugin settings, not as secrets, so a token or
password written into an argument would sit there in plain text
and show up in the settings UI, logs and sensor output. If a
command needs a credential, keep it out of the command itself:
put it in a script file on the device with tight permissions and
have the command call that script instead.

## Examples

| What | Executable | Arguments |
| --- | --- | --- |
| Re-apply rotation | `/system/bin/settings` | `put system user_rotation 1` |
| Launch an app | `/system/bin/am` | `start -n com.example/.MainActivity` |
| Start a service | `/system/bin/am` | `startservice -n com.example/.MyService` |
| Grant a permission | `/system/bin/pm` | `grant com.example android.permission.POST_NOTIFICATIONS` |
| Read process identity (test) | `/system/bin/id` | *(empty)* |

Replace the package and class names with the app you actually mean.
Check a command with **Run commands now** before relying on it at
startup, and prefer idempotent commands: settings, grants and
launches are; toggles are not, because they would flip again on
every run.

## Requirements

Kiosk Satellite 2026.10.40 or newer, Android 7.0 (API 24) or newer.
Root commands need a rooted device with a silent `su` grant for
Kiosk Satellite. Shizuku commands need Shizuku installed, running
and granted to Kiosk Satellite (**Settings > Device > Shizuku**).

## Building

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The ZIP appears in `dist/`. Releases are built by GitHub Actions
from a version tag; Kiosk Satellite only accepts release assets
uploaded by that workflow for GitHub installs.

## License

Apache-2.0. Kiosk Satellite itself has its own application license,
and its plugin SDK interfaces are Apache-2.0 under the template's
PLUGIN-EXCEPTION.

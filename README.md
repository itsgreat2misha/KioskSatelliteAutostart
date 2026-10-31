# Autostart for Kiosk Satellite

Run commands you choose every time Kiosk Satellite starts, through root
(`su`) or Shizuku. A command can run on every start, or only once per
boot, so a Kiosk restart does not repeat it.

Use it for anything that needs re-applying after a reboot: a setting a
device drops on reboot, launching an app or service, or granting a
permission after Kiosk starts.

## Installation

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on
   **Enable Plugins**.
2. Choose **Add plugin**, paste
   `https://github.com/itsgreat2misha/KioskSatelliteAutostart` and choose
   **Preview**.
3. Review the plugin and choose **Trust and install**.
4. Enable **Autostart** from its entry row, then open its settings and
   add commands.

Once it is listed in Community Plugins, you will also be able to find
it by searching for `Autostart` in **Settings > Plugin Manager >
Browse plugins**.

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
- **Commands** — a list of up to 32. Each row has a label, an enabled
  toggle, an executable (absolute path, for example
  `/system/bin/settings`), its arguments (one string, split
  quote-aware, for example `put system user_rotation 1`), a backend,
  a run mode and a timeout in seconds (1–30).

Buttons on the settings page:

- **Run commands now** — runs all enabled commands now, ignoring
  Once per boot tracking. A manual run does not mark commands as run
  for this boot, so testing a command does not consume its
  once-per-boot slot.
- **Clear run state** — clears Once per boot tracking, so those
  commands run again on the next Kiosk start.
- **Check backends** — reports whether root (`su`) and Shizuku are
  available on this kiosk.

To run a single command on its own, open its row, turn **Run now** on
and save. It runs once, ignoring Once per boot tracking, without
marking it as run for this boot. A disabled command does not run.

**Run all autostart commands** is also available as an action you can
assign to a gesture, the kiosk drawer or Home Assistant. It does the
same as **Run commands now**.

## How it works

Automatic commands run when the Kiosk app starts, on a background
thread, one after another. A long command cannot stall Kiosk, a
crashing command cannot take Kiosk down, and a failed command does
not stop later commands. Every result is logged, summarised in the
plugin status and published as the `Autostart status` and
`Autostart last run` text sensors.

Enabling the plugin alone does not run commands; they run on the
next Kiosk app start. Only successful automatic runs
are marked, so a failed command retries on the next Kiosk start.

Shizuku must be installed, running and granted to Kiosk Satellite
(**Settings > Device > Shizuku**) before a Shizuku command can run.
Autostart does not start Shizuku, and it does not substitute `su`
when a command is set to Shizuku only. When shell syntax is genuinely
needed, use `/system/bin/sh` with `-c` as the executable.

Check a command with **Run commands now** before relying on it at
startup, and prefer idempotent commands: settings, grants and
launches are; toggles are not, because they would flip again on
every run.

## Examples

| What | Executable | Arguments |
| --- | --- | --- |
| Re-apply rotation | `/system/bin/settings` | `put system user_rotation 1` |
| Launch an app | `/system/bin/am` | `start -n com.example/.MainActivity` |
| Start a service | `/system/bin/am` | `startservice -n com.example/.MyService` |
| Grant a permission | `/system/bin/pm` | `grant com.example android.permission.POST_NOTIFICATIONS` |

Replace the package and class names with the app you actually mean.

## Security

This plugin runs inside Kiosk Satellite with its privileges. A command
set to Root runs as root, and a command set to Shizuku runs as
Shizuku's shell or root user. Only install and enable it if you trust
the commands you enter and anyone else who can edit this plugin's
settings. The plugin sends no data anywhere: it runs local commands
and reports only to Kiosk Satellite's log, status and text sensors.

Do not put secrets in commands. Command executables and arguments
are stored as plain plugin settings, not as secrets, so a token or
password written into an argument would sit there in plain text
and show up in the settings UI, logs and sensor output. If a
command needs a credential, keep it out of the command itself:
put it in a script file on the device with tight permissions and
have the command call that script instead.

## Requirements

Kiosk Satellite 2026.10.40 or newer, Android 7.0 (API 24) or newer.
Root commands need a rooted device with a silent `su` grant for
Kiosk Satellite.

## Building

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The ZIP appears in `dist/`. Releases are built by GitHub Actions
from a version tag.

## License

Apache-2.0.

# Task Scheduler for Kiosk Satellite

Run commands you choose on boot, when Kiosk Satellite starts, or on a
schedule — every so often, daily, or weekly — through root (`su`) or
Shizuku.

Use boot and start tasks for anything that needs re-applying after a
reboot: a setting a device drops on reboot, launching an app or
service, or granting a permission. Use scheduled tasks for periodic
housekeeping: restarting a flaky service, running a health check, or
clearing a cache overnight.

## Installation

Use the button below to open this plugin in your Kiosk Satellite,
where you review it and choose **Trust and install**:

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=itsgreat2misha/KioskSatelliteAutostart)

Then enable **Task Scheduler** from its entry row, open its settings
and add tasks.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on
   **Enable Plugins**.
2. Choose **Add plugin**, paste
   `https://github.com/itsgreat2misha/KioskSatelliteAutostart` and choose
   **Preview**.
3. Review the plugin and choose **Trust and install**.
4. Enable **Task Scheduler** from its entry row, then open its
   settings and add tasks.

This plugin is not yet listed in Community Plugins — a submission is
pending. Until it is listed, install it with the button above or
manually; searching **Browse plugins** will not find it yet.

For local testing only, **Plugin Manager > Developer Tools > Install
from ZIP** accepts the ZIP built by `python3 tools/build.py`.

## Upgrading and uninstalling

To upgrade, install the newer release the same way; your tasks
and settings are kept. Uninstalling the plugin deletes its settings,
including all saved tasks, so copy anything you want to keep
before you uninstall. The one exception is 1.4.2 — see the next
section.

### Coming from 1.3.x–1.4.1?

Schedule and backend are per-task settings now, and the
plugin-wide **Default backend**, **Default schedule** and
**Startup delay** settings are gone for good. Versions 1.4.0 and
1.4.1 migrated tasks automatically on the first start: a task
that was set to `Default` took the old **Default schedule** /
**Default backend** value, and the old **Startup delay** became
the **Delay** of **On boot** and **On Kiosk start** tasks.

1.4.2 removes the legacy settings and the `Default` options, and
it cannot load a configuration stored by an earlier version:
Kiosk Satellite validates stored settings against the plugin
before it starts, and a configuration from 1.3.x–1.4.1 still
contains the removed settings (and may contain tasks set to
`Default`). Upgrading to 1.4.2 is therefore a clean install:
note your tasks, remove the plugin, install 1.4.2 and re-enter
the tasks. When you re-enter them, set each task's schedule and
backend directly — the values `Default` stood for — and give
**On boot** and **On Kiosk start** tasks the **Delay** you
previously had as **Startup delay**, if any.

### Coming from Autostart?

This plugin replaces the **Autostart** plugin (the plugin ID changed
from `kiosk-autostart` to `ks-task-scheduler`). Settings do **not**
carry over: Autostart's commands will not appear here. Note your
Autostart commands first, uninstall Autostart, install Task
Scheduler, and re-enter them as tasks — your existing commands map
one-to-one: *Once per boot* becomes the **On boot** schedule and
*Every start* becomes **On Kiosk start**.

## Settings

- **Tasks** — a list of up to 32. Each row starts with a label, an
  executable (absolute path, for example `/system/bin/settings`) and
  its arguments (one string, split quote-aware, for example
  `put system user_rotation 1`), then its schedule and the schedule's
  fields (below), a backend, a timeout in seconds (1–30), and an
  enabled toggle.

Each task's **backend** is `Auto` (root when `su` works, otherwise
Shizuku), `Root` or `Shizuku`.

Each task's **schedule** is one of:

- **On boot** — run once per boot; a Kiosk restart in the same boot
  does not repeat it.
- **On Kiosk start** — run at every Kiosk app start.
- **Interval** — run every **interval** minutes after the previous
  run (5 minutes to one week). A new interval task runs soon after
  it is added, then anchors on that run.
- **Daily** — run at the **time of day** (`HH:MM`, local time).
- **Weekly** — run on the chosen **weekday** at the **time of day**.
- **Manual** — never runs on its own. Run it with **Run tasks now**,
  the **Run all scheduled tasks** action, or its per-row **Run now**
  toggle.

Scheduled (Interval/Daily/Weekly) tasks also have:

- **If a scheduled run is missed** — `Catch up once` (the default)
  runs the task once at the next opportunity if its time passed
  while Kiosk was not running; it never replays more than one missed
  run. `Skip` abandons a run that is well past its time (more than
  15 minutes for daily/weekly, a full interval for interval tasks).
- **Jitter** — optionally delay each scheduled run by a fixed
  per-occurrence amount between 0 and this many minutes, so a fleet
  of kiosks does not all run the same task at the same moment.

Every task except a **Manual** one also has a **Delay**: an
automatic run waits this many seconds (0–300) immediately before
running the task, so tasks that fire at the same moment can be
staggered. Manual runs ignore the delay.

The task editor shows only the fields that apply to the schedule
you picked: an **On boot** task, for example, has no interval, time
of day or weekday fields, and a **Manual** task shows none of the
schedule fields at all. In the task list, each task shows its
schedule type under its name (for example `Weekly` or `On boot`)
instead of its executable path.

Buttons on the settings page, both in the **Advanced** section:

- **Run tasks now** — runs all enabled tasks now, whatever their
  schedule. A manual run does not mark a boot task as run for this
  boot and does not move a scheduled task's next run, so testing a
  task does not disturb its schedule.
- **Clear scheduler state** — clears Once per boot tracking and
  scheduled tasks' anchors, so boot tasks run again on the next
  Kiosk start and scheduled tasks re-anchor.

The **Advanced** section also holds the **Logging level**
setting: `Informational` (the default) keeps the plugin log to
run results, while `Verbose` adds the scheduling decisions
behind each pass — why a task ran, waited or was skipped, the
anchors read and saved, backend probes and choices, and delay
waits — for troubleshooting. It changes only the log, never
what runs. The plugin-managed state settings live in the same
section and stay hidden, as editing them by hand is not needed.

Backend availability is checked automatically when Kiosk starts
and when settings are saved, and the result is shown in the plugin
status at the top of the settings page — there is no button for it.

To run a single task on its own, open its row, turn **Run now** on
and save. It runs once, ignoring its schedule, without marking it
as run for this boot or moving its next scheduled run. A disabled
task does not run.

**Run all scheduled tasks** is also available as an action you can
assign to a gesture, the kiosk drawer or Home Assistant. It does the
same as **Run tasks now**.

## How it works

Tasks run on a background thread, one after another. A long task
cannot stall Kiosk, a crashing task cannot take Kiosk down, and a
failed task does not stop later tasks. Every result is logged,
summarised in the plugin status and published as the
`Task Scheduler status`, `Task Scheduler last run` and
`Task Scheduler next run` text sensors.

Start and boot tasks run when the Kiosk app starts. Enabling the
plugin alone does not run them; they run on the next Kiosk app
start. Only successful start/boot runs are marked, so a failed boot
task retries on the next Kiosk start. A failed *scheduled* task
waits for its next scheduled time instead of retrying immediately.

A scheduler thread inside the plugin watches the scheduled tasks'
due times and runs them when they come due. It remembers each
scheduled task's last run in a hidden plugin-managed setting, so
due times survive Kiosk and plugin restarts. Editing a task's
schedule (type, interval, time, or weekday) starts that task's
schedule fresh; other edits keep its place.

Task Scheduler does not start Shizuku, and it does not substitute
`su` when a task is set to Shizuku only. When shell syntax is
genuinely needed, use `/system/bin/sh` with `-c` as the executable.

Check a task with **Run tasks now** before relying on it, and
prefer idempotent tasks: settings, grants and launches are; toggles
are not, because they would flip again on every run.

## Examples

On boot / on Kiosk start:

| What | Schedule | Executable | Arguments |
| --- | --- | --- | --- |
| Re-apply rotation | On boot | `/system/bin/settings` | `put system user_rotation 1` |
| Launch an app | On Kiosk start | `/system/bin/am` | `start -n com.example/.MainActivity` |
| Grant a permission | On boot | `/system/bin/pm` | `grant com.example android.permission.POST_NOTIFICATIONS` |

Scheduled:

| What | Schedule | Executable | Arguments |
| --- | --- | --- | --- |
| Restart a service every 6 hours | Interval, 360 min | `/system/bin/am` | `startservice -n com.example/.MyService` |
| Clear an app's cache nightly | Daily, 03:15 | `/system/bin/pm` | `clear com.example` |
| Weekly health check | Weekly, Monday 08:00 | `/system/bin/sh` | `-c "echo ok > /data/local/tmp/health"` |

Replace the package and class names with the app you actually mean.

## Security

This plugin runs inside Kiosk Satellite with its privileges. A task
set to Root runs as root, and a task set to Shizuku runs as
Shizuku's shell or root user. Only install and enable it if you trust
the tasks you enter and anyone else who can edit this plugin's
settings. The plugin sends no data anywhere: it runs local tasks
and reports only to Kiosk Satellite's log, status and text sensors.

Do not put secrets in tasks. Task executables and arguments
are stored as plain plugin settings, not as secrets, so a token or
password written into an argument would sit there in plain text
and show up in the settings UI, logs and sensor output. If a
task needs a credential, keep it out of the task itself:
put it in a script file on the device with tight permissions and
have the task call that script instead.

## Requirements

Kiosk Satellite 2026.10.40 or newer, Android 7.0 (API 24) or newer.
Tasks need either root or Shizuku: root tasks need a rooted
device with a silent `su` grant for Kiosk Satellite, and Shizuku
tasks need Shizuku installed, running, and granted to Kiosk
Satellite (**Settings > Device > Shizuku**).

## Troubleshooting

- **A task did not run at startup.** Enabling the plugin alone
  does not run start/boot tasks; they run on the next Kiosk app
  start. Test the task first with **Run tasks now**.
- **A scheduled task did not run at its time.** See the
  best-effort timing limitation below. Check the
  `Task Scheduler next run` sensor for when the plugin thinks the
  next run is, and check that the task's time of day is in
  24-hour `HH:MM` form — a daily or weekly task with an invalid
  time never runs.
- **A backend is unavailable.** The plugin status at the top of
  the settings page reports whether root (`su`) and Shizuku are
  available on this kiosk; it is checked automatically when Kiosk
  starts and when settings are saved. For Shizuku tasks, make sure
  Shizuku is installed, running, and granted to Kiosk Satellite
  (**Settings > Device > Shizuku**) — see Limitations below.
- **An `On boot` task was skipped.** Only successful
  automatic runs are marked as run for this boot, so a failed
  task retries on the next Kiosk start. To run a skipped task
  again in the same boot, use **Clear scheduler state**, or run it
  manually — manual runs ignore Once per boot tracking.
- **Shell syntax (pipes, redirection, `&&`) did not work.**
  Tasks run directly, without a shell. Use `/system/bin/sh`
  with `-c` as the executable when shell syntax is genuinely
  needed.
- **A task failed but later tasks still ran.** That is
  expected: tasks run one after another on a background thread,
  and a failed task does not stop later tasks. Every result
  is logged and summarised in the plugin status and the
  `Task Scheduler status` and `Task Scheduler last run` text
  sensors.
- **You need more detail about why a task did or did not run.**
  Set **Logging level** to **Verbose** in the **Advanced**
  section and reproduce the run: the plugin log then shows the
  decision behind each task in each pass. Set it back to
  **Informational** when you are done.

## Limitations

- Tasks need either root or Shizuku (see Requirements). With
  the `Auto` backend the plugin uses root when `su` works,
  otherwise Shizuku; a task set to `Root` or `Shizuku` only
  uses that backend.
- The plugin does not start Shizuku itself, and it does not
  substitute `su` when a task is set to Shizuku only. Shizuku
  must already be running when the task runs.
- **Scheduled runs are best-effort, not exact-time alarms.** The
  scheduler runs inside Kiosk Satellite's process, and plugins
  cannot use Android's alarm or job-scheduling APIs or hold the
  device awake. If the device is asleep or Kiosk Satellite is not
  running at a task's due time, the run happens late — when Kiosk
  next runs and notices — or, with the `Skip` missed-run policy,
  not at all. Do not use scheduled tasks for anything where
  running late (or, with `Skip`, not running) would be a problem,
  such as alarms or time-critical actions.
- **Missed runs are never replayed more than once.** With
  `Catch up once`, a task whose time passed while Kiosk was off
  runs a single time at the next opportunity, however many runs
  were missed.
- On the day daylight saving time ends, an ambiguous daily or
  weekly time (one that occurs twice) runs at its second,
  standard-time occurrence; a time inside the spring-forward gap
  runs shifted forward by the gap.
- Tasks run directly, without a shell: each task is a single
  executable (absolute path) plus one arguments string. Use
  `/system/bin/sh` with `-c` for shell syntax.
- At most 32 tasks, each with a timeout of 1–30 seconds. This is
  a scheduler for short commands, not for long-running jobs.
- Task executables and arguments are stored as plain plugin
  settings, not as secrets — do not put secrets in tasks (see
  Security above).

## Building

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The ZIP appears in `dist/`. To release: update the CHANGELOG,
push a `v*` tag (for example `v1.3.0`), and the Release workflow
tests and builds the plugin, creates the GitHub release for the
tag — with the CHANGELOG section for that version as its notes
when one exists — and attaches the ZIP, its checksum and the
manifest, all uploaded by GitHub Actions.

## License

Apache-2.0.

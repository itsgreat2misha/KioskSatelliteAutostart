# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.1] - 2026-11-04

### Fixed

- Root availability is no longer lost for the rest of the process
  when the first probe finds no working `su`: only a working form
  is cached, a failed probe is retried on later passes (at most
  once a minute), and a root execution that fails because `su`
  itself could not start drops the cached form so the next pass
  re-probes. A root stack that starts just after Kiosk Satellite,
  or comes back after a crash, is picked up without restarting
  Kiosk. The automatic backend check also refreshes when
  Shizuku's state changes.
- The declared action is now titled **Run all enabled tasks
  now**, matching what it does — every enabled task runs,
  whatever its schedule. Its ID (`runNow`) is unchanged.
- The **Scheduler state** description now says a task's last
  *accounted* run (its last successful run, or a `Skip` resync),
  which is what the anchor records.
- Documentation corrected and completed: the
  examples no longer present `pm clear` as clearing an app's
  cache (it wipes app data); editing a task's executable or
  arguments is documented as starting its schedule fresh, like a
  schedule edit; the backend-check description matches the
  throttled, informational check; the plain-settings warning no
  longer claims arguments appear in sensor output; task defaults,
  the jitter range and the Shizuku argument limits are stated;
  and the scheduling semantics a user needs to predict a run —
  first run after adding or re-enabling a task, missed-run scope,
  the `Skip` window, what a result contains — are spelled out.

### Removed

- The pre-1.4 settings migration (task rows storing `Default`,
  resolved through the removed plugin-wide settings, with its
  write-back) is gone. It was unreachable — a stored
  configuration containing those settings or a `Default` row
  cannot load since 1.4.2 — and current configurations parse
  exactly as before.
- The README no longer carries upgrade notes for pre-release
  versions or for the Autostart plugin. The uninstall note
  remains: uninstalling deletes the plugin's settings and
  tasks.

## [1.5.0] - 2026-11-04

### Added

- **Logging level** setting (**Informational** / **Verbose**),
  in the **Advanced** section. **Verbose** adds the scheduling
  decisions behind each pass to the plugin log — per-task
  verdicts and reasons, the anchors read and saved, backend
  probe results and per-task backend choices, and delay waits —
  for troubleshooting. It changes only the log, never what
  runs. Existing installs gain the setting at its
  **Informational** default automatically.

### Changed

- The **Advanced** section returns, holding **Logging level**
  and both buttons (**Run tasks now** and **Clear scheduler
  state**). The plugin-managed state settings live there too
  and are all hidden again: their hidden condition now anchors
  on **Logging level** instead of **Session process id**, so
  that field no longer has to stay visible. The **Tasks**
  section is once more just the task list and the readings.

## [1.4.3] - 2026-11-04

### Changed

- The settings page now has a single **Tasks** section:
  **Clear scheduler state** sits directly under **Run tasks
  now**, and the plugin-managed state settings live there too.
  The **Advanced** section is gone. Of the managed settings,
  only **Session process id** is visible — the other two anchor
  their hidden condition on it.

## [1.4.2] - 2026-11-04

### Removed

- The legacy plugin-wide **Default backend**, **Default schedule**
  and **Startup delay** settings and the `Default` schedule/backend
  option for tasks — declared again in 1.4.1 only so that 1.3.x
  upgrades would load — are gone for good. Schedule and backend
  are per-task settings only.
- **Breaking:** this version cannot load a stored configuration
  that still contains those settings or a task set to `Default`,
  which is every install that ran 1.3.x–1.4.1: Kiosk Satellite
  validates stored settings against the manifest before any
  plugin code runs, so there is nothing left for the migration to
  resolve. Upgrading requires a clean install — remove the
  plugin (which deletes its settings), install 1.4.2 and
  re-enter your tasks. Fresh installs are unaffected.

### Changed

- The **Session process id** setting now appears in the
  **Advanced** group. It is a small plugin-managed value and
  stays visible because the other managed state settings anchor
  their hidden condition on it.

## [1.4.1] - 2026-11-04

### Fixed

- Upgrading from 1.3.x could leave the plugin disabled. Kiosk
  Satellite validates the stored settings against the manifest
  before any plugin code runs, and 1.4.0 no longer declared the
  plugin-wide settings or the `Default` schedule/backend option
  that stored 1.3.x configurations can still contain, so the
  validation failed and the automatic migration never got to run.
  This transition release declares the legacy **Default backend**,
  **Default schedule** and **Startup delay** settings and the
  `Default` options again, so existing installs load and migrate.
  The legacy settings are ignored apart from the one-time
  migration and will be removed in a later release.

## [1.4.0] - 2026-11-04

### Added

- **Manual** schedule: a task that never runs on its own — only
  when you run it, via **Run tasks now**, the **Run all scheduled
  tasks** action, or its per-row **Run now** toggle.
- Per-task **Delay** (0–300 s): an automatic run waits this long
  immediately before running the task, so tasks that fire together
  can be staggered. Manual runs ignore it.

### Changed

- Schedule and backend are now set per task only. The plugin-wide
  **Default backend**, **Default schedule** and **Startup delay**
  settings are removed. Existing tasks are migrated automatically
  on the first start of the new version: a task set to `Default`
  takes the old plugin-wide value, and the old **Startup delay**
  becomes the **Delay** of **On boot** and **On Kiosk start** tasks.

### Removed

- The **Check backends** button. Backend availability is now
  checked automatically when Kiosk starts and when settings are
  saved, and shown in the plugin status.

## [1.3.2] - 2026-11-04

### Fixed

- The task list now shows each task's schedule type under its name
  (for example "Weekly" or "On boot"). The composed schedule summary
  added in 1.3.1 (the plugin-maintained `scheduleSummary` field) did
  not render on device, so that mechanism was removed entirely.

### Changed

- The task editor now leads with **Executable** and **Arguments**;
  the schedule type and its fields follow, then the backend, timeout
  and enabled/run toggles.

## [1.3.1] - 2026-11-04

### Added

- The task editor now shows only the schedule fields that apply to
  the chosen schedule type (for example, an On boot task no longer
  shows the interval, time of day or weekday fields). A task set to
  Default shows all of them.
- The task list now shows each task's schedule under its name (for
  example "Weekly on Friday at 5:00 PM") instead of its executable
  path.

## [1.3.0] - 2026-11-04

### Changed

- The plugin is now **Task Scheduler** (plugin ID
  `ks-task-scheduler`, previously Autostart / `kiosk-autostart`).
  Settings do not carry over from Autostart; commands must be
  re-entered as tasks (see the README's "Coming from Autostart?"
  section).
- Commands are now tasks with a per-task schedule: **On boot**
  (previously Once per boot), **On Kiosk start** (previously Every
  start), plus new **Interval**, **Daily** and **Weekly** schedules
  with a missed-run policy (`Catch up once` or `Skip`) and optional
  per-task jitter. The settings key `commands` is now `tasks` and
  `defaultRunMode` is now `defaultSchedule`.
- **Clear run state** is now **Clear scheduler state** and also
  clears scheduled tasks' anchors.
- The release workflow now runs on a `v*` tag push and creates the
  GitHub release itself (previously it ran when a release was
  published and attached assets to it).

### Added

- A scheduler thread that runs Interval, Daily and Weekly tasks
  when they come due while Kiosk Satellite is running, with each
  task's last run persisted in a hidden plugin-managed setting.
  Scheduled timing is best-effort (see the README's Limitations).
- `Task Scheduler next run` text sensor alongside the renamed
  status and last run sensors.
- New clock icon.

## [1.2.1] - 2026-10-31

### Added

- Plugin icon (`assets/icon.png`).

### Changed

- README warns not to put secrets in commands, since command
  executables and arguments are stored as plain plugin settings.

## [1.2.0] - 2026-10-30

### Changed

- Reverted to a single declared action (`Run all autostart
  commands`); the separate selected-command action from 1.1.0 was
  removed.
- Per-command runs now use a per-row **Run now** toggle on each
  command instead of the **Run one command** selector.

### Removed

- The **Run one command** selector setting.

## [1.1.0] - 2026-10-30

### Added

- Per-command run: a **Run one command** selector plus **Run
  selected command**, running only that command without marking it
  as run for this boot.
- **Clear run state** button, clearing Once per boot tracking.
- Session process ID tracking, so a plugin session restart in the
  same Kiosk process does not run commands again.

### Changed

- Run state is now a hidden, plugin-managed setting instead of a
  visible do-not-edit field.
- Enabling the plugin alone no longer runs commands; they run on
  the next Kiosk app start.

### Fixed

- Root backend falls back to `su 0` when plain `su` does not yield
  root.

## [1.0.0] - 2026-10-30

### Added

- Initial release: run user-chosen commands every time Kiosk
  Satellite starts, through root (`su`) or Shizuku.
- Per-command backend (`Auto`, `Root`, `Shizuku`) and run mode
  (`Once per boot` or `Every start`), with plugin-wide defaults, a
  startup delay, and a per-command timeout.
- **Run commands now** and **Check backends** buttons, plus the
  `Autostart status` and `Autostart last run` text sensors.
- GitHub Actions workflow building release assets from a version
  tag.

[1.2.1]: https://github.com/itsgreat2misha/KioskSatelliteAutostart/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/itsgreat2misha/KioskSatelliteAutostart/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/itsgreat2misha/KioskSatelliteAutostart/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/itsgreat2misha/KioskSatelliteAutostart/releases/tag/v1.0.0

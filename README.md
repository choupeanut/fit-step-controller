# Fit Step Controller

Fit Step Controller is an Android test app for writing verified step records to Health Connect. It does not use the deprecated Google Fit Fitness API. Google Fit is an optional consumer of Health Connect data and may display a different, delayed, or source-prioritized aggregate.

## Current features

### Mode 1 — paced walking

- Plans a target from 3–12 km/h with a configurable stride length.
- Shows distance, required duration, current speed, remaining time, and estimated finish time.
- Applies speed-slider changes live while running; already elapsed time keeps the previous rate.
- Writes verified cadence chunks through a persistent `dataSync` foreground service.
- Persists progress, pending records, fractional cadence, and current speed for service recreation.
- Supports pause, resume, stop, automatic completion, sticky restoration, and background CPU wake protection.

### Mode 2 — empty-window backfill

- Scans all readable Health Connect `StepsRecord` sources for the local-time range `12:00` to now.
- Treats the union of existing raw record intervals as occupied and shows the remaining empty windows.
- Calculates a theoretical upper bound at `10 km/h` with a `0.35 m` stride (`7.9365 steps/sec`, about `28,571 steps/hour`).
- Accepts a requested count up to the current capacity and fills empty windows oldest-first.
- Re-scans before each write, uses deterministic batch record IDs, verifies each exact record, and reports partial completion if the provider changes during the batch.
- If the current time is before local noon, the available range is empty.

### Advanced direct entry

The original direct-entry flow remains available for exact manual placement of one record ending at the current time. It is labelled as advanced because it does not perform empty-window planning.

## Data and correctness model

Health Connect is the only write target. The app verifies its own exact raw record—count, interval, and client record ID—before reporting success. Aggregate totals are diagnostics only.

Mode 2 uses raw records from every granted source and deliberately treats their union as occupied. An empty window means that Health Connect returned no `StepsRecord` intersecting the window; it is not proof that the user was physically inactive. `StepsRecord` contains an interval and a total count, not the exact second of every step.

Health Connect read requests are paginated and use the empty data-origin filter for the all-source scan. Aggregate reads can deduplicate Activity data according to user-selected source priority, so Google Fit and Health Connect dashboard totals may differ from the app's exact records.

## Permissions and setup

1. Install the APK and open Health Connect permissions from the app.
2. Grant this app read and write access to steps.
3. For Google Fit display, enable Google Fit's Health Connect sync and grant Google Fit access to steps separately.
4. For long Mode 1 runs, set the app battery usage to **Unrestricted** on devices with aggressive vendor power management.

## Build and test locally

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

The repository's GitHub Actions workflow runs unit tests, builds both APK variants, uploads artifacts, and publishes a release when a `v*` tag is pushed.

## Manual validation checklist

- Grant Health Connect permissions and refresh Mode 2. Confirm the displayed local noon-to-now range, empty-window count, available duration, and theoretical capacity.
- Request a value within the displayed capacity. Confirm the resulting app-origin records are inside previously empty windows and do not overlap existing records.
- Start Mode 1 with a short target. Confirm the first verified cadence update, move the speed slider while running, and confirm the notification/UI ETA changes.
- Lock the screen and remove the app task from recents. Confirm the foreground notification and persisted progress continue.
- Pause for more than one cadence interval, resume, and confirm paused time is not written. Revoke Health Connect permission during a run and confirm the session reports failure without advancing confirmed steps.
- If Google Fit is part of the test, compare it only as a diagnostic after allowing synchronization time.

## Platform limits

Android battery managers, user force-stop, and Android 15 foreground-service policy limits can still interrupt work. Mode 1 is capped at five hours to stay below the Android 15 `dataSync` service limit. No Android API can guarantee survival after a user force-stop.

## AI-assisted development

See [AI_USAGE.md](AI_USAGE.md) for the repository's AI-agent workflow, architectural invariants, validation commands, and Health Connect safety rules.

## License and test signing

The debug and release APKs use the committed test signing configuration for device testing. It is not a production Play Store signing key.

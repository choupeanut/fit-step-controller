# Fit Step Controller

Fit Step Controller is an Android test app for writing verified step records to Health Connect. It does not use the deprecated Google Fit Fitness API. Google Fit is an optional consumer of Health Connect data and may display a different, delayed, or source-prioritized aggregate.

## App Icon

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" alt="Fit Step Controller cat paw app icon" width="220">
</p>

The launcher artwork is a two-tone cat paw: a teal main paw for the app identity and a coral accent paw to represent an active step. The image above is the transparent foreground artwork used by the adaptive launcher icon.

## Current features

### Mode 1 — paced walking

- Plans a target from 3–12 km/h with a configurable stride length.
- Shows distance, required duration, current speed, remaining time, and estimated finish time.
- Applies speed-slider changes live while running; already elapsed time keeps the previous rate.
- Writes verified cadence chunks through a persistent `dataSync` foreground service.
- Persists progress, pending records, fractional cadence, and current speed for service recreation.
- Supports pause, resume, stop, automatic completion, sticky restoration, and background CPU wake protection.

### Mode 2 — empty-window backfill

- Scans all readable Health Connect `StepsRecord` sources for the local-time range `00:00` (midnight) to now.
- Treats the union of existing raw record intervals as occupied and shows the remaining empty windows.
- Calculates a theoretical upper bound at `10 km/h` with a `0.35 m` stride (`7.9365 steps/sec`, about `28,571 steps/hour`).
- Accepts a requested count up to the current capacity and fills empty windows oldest-first.
- Re-scans before each write, uses deterministic batch record IDs, verifies each exact record, and reports partial completion if the provider changes during the batch.
- The scan starts at the beginning of the device-local calendar day; at exactly `00:00` the range has no elapsed duration yet.

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

## Enable Google Fit and Health Connect

The app writes only to Health Connect. Google Fit is an optional viewer/sync destination; it is not the app's write-success check.

### 1. Open Health Connect

- **Android 14 and newer:** open **Settings → Security & privacy → Privacy controls → Health Connect**. Health Connect is integrated into the system on Android 14 and newer.
- **Android 13 and older:** install **Health Connect** from Google Play, then open it from **Settings → Apps → Health Connect → Open**.

See Google's [Health Connect access instructions](https://support.google.com/android/answer/13770320?hl=en) for Android-version differences.

### 2. Grant Fit Step Controller access

1. Open **Health Connect → App permissions**.
2. Select **Fit Step Controller**.
3. Allow both **Read steps** and **Write steps**.
4. Return to the app. The Health Connect banner should show that permissions are enabled.
5. Open **空檔補步** and tap **掃描今日空檔** (the scan covers the device-local day from `00:00` to now).

Health Connect lets you manage an app's complete or individual read/write permissions from **App permissions**; see Google's [connected-app permission guide](https://support.google.com/android/answer/12201230?hl=en).

### 3. Connect Google Fit to Health Connect

1. Open **Google Fit**.
2. Tap **Profile → Settings**.
3. Under **Health Connect**, turn on **Sync Fit with Health Connect**.
4. If prompted, allow Google Fit to access the relevant Health Connect data types.
5. In Health Connect, open **App permissions → Google Fit** to review or change its access.

Google documents this flow in [Health Connect on Google Fit](https://support.google.com/fit/answer/12830119?hl=en). If you are connecting another app directly to Google Fit, use Google's [connected-app instructions](https://support.google.com/fit/answer/6098255?co=GENIE.Platform%3DAndroid&hl=en).

### 4. Verify the data path

1. In Fit Step Controller, run Mode 1 or Mode 2 and wait for the app's exact Health Connect verification to complete.
2. Open **Health Connect → Data and access → Activity → Steps** and inspect the entries and app sources.
3. Open Google Fit and allow time for the Health Connect sync to appear.

Health Connect's totals can combine multiple sources and apply source priority. Google Fit's displayed total can therefore differ from this app's exact raw-record verification; use the app's success message and the Health Connect entry as the authoritative write check. Google's [Health Connect data-source guide](https://support.google.com/android/answer/12990553?hl=en) explains source priority and aggregation.

### Troubleshooting

- If the app's **設定** button is shown, reopen Health Connect and grant both step permissions to **Fit Step Controller**.
- If Google Fit is not showing a new value, first confirm the record under Health Connect's **Steps** data page, then confirm that **Sync Fit with Health Connect** remains enabled.
- If multiple apps provide steps, review **Health Connect → Manage data → Data sources and priority** before comparing totals.

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

- Grant Health Connect permissions and refresh Mode 2. Confirm the displayed local midnight-to-now range, empty-window count, available duration, and theoretical capacity.
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

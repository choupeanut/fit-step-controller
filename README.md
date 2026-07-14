# Fit Step Controller

Modern Android step-entry test app built with Kotlin, Jetpack Compose, and Health Connect.

## Features

- Mode 1: paced walking from 3 km/h to 12 km/h with a step target and automatic stop.
- Mode 2: scans today's local 12:00-to-now empty `StepsRecord` windows, shows a theoretical 10 km/h capacity, and fills requested steps oldest-first without overlapping existing records.
- Advanced direct entry: writes a realistic non-zero time interval immediately when exact manual placement is required.
- Mode 1 continues from a foreground service after the app is backgrounded or its task is removed, with persisted recovery and a bounded CPU wake lock while running.
- Mode 1 displays remaining duration and estimated finish time; its speed slider applies live while a session is running.
- Health Connect read/write permission flow and exact app-record read-back verification.
- GitHub Actions APK build and release workflow for `v*` tags.

## Data Path

The app writes manually entered `StepsRecord` entries to Health Connect. Google Fit can display those records only when the user enables Google Fit's Health Connect sync on the Android device. The app treats its own exact Health Connect record as the write success boundary; Google Fit's displayed total is a separate, source-prioritized aggregate.

Health Connect and Google Fit do not treat displayed step count as a raw append-only counter. Both systems merge step data from multiple sources, avoid duplicate activity intervals, and may show a delayed or estimated total. Mode 2 reads raw `StepsRecord` intervals from all granted sources and conservatively treats their union as occupied; its "empty" windows mean no step record was found, not proof that the user was physically inactive. Advanced direct entry stores each submission in a realistic interval ending at the current time and reports both this app's exact raw record and Health Connect's aggregate diagnostic for that interval.

This project intentionally does not implement game-specific automation, anti-detection behavior, or claims about third-party app reward systems.

## Local Build

```bash
./gradlew test assembleDebug
```

If Android SDK is not discovered automatically, create `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Release

The workflow builds APKs for pushes and pull requests. Pushing a tag such as `v1.0.0` also uploads APK artifacts and can be used to create a GitHub release.

## Google Fit display

This app does not use the deprecated Google Fit Fitness API. To display records in Google Fit, enable Google Fit's Health Connect sync and grant Google Fit access to steps in Health Connect. Health Connect may deduplicate overlapping Activity records according to the user's source priority, so Google Fit's aggregate can differ from this app's exact record count.

## Manual validation on Android 14/15

1. Grant this app Health Connect read/write access to steps. In Mode 2, refresh the local 12:00-to-now scan, confirm the displayed empty windows and theoretical capacity (`10 km/h`, `0.35 m` stride), then request a value no greater than the current limit. Confirm Health Connect contains the app's exact records in non-overlapping empty windows. The result is based on exact raw records, not on Google Fit's aggregate.
2. If exact end-at-now placement is needed, use Advanced direct entry to write `3000` steps and confirm in Health Connect that one exact record from this app contains `3000` steps and ends at the current time. The app's success message is based on this raw record, not on Google Fit's aggregate.
3. Enable Google Fit's Health Connect sync if its dashboard is part of the test. Allow for sync delay and compare the display only as a diagnostic; source priority or deduplication can make its total differ.
4. Start paced mode with a short target (for example, `1000` steps), note the displayed duration and estimated finish, then move the speed slider while running. Confirm the next progress/notification update reflects the new speed and ETA. Wait through the first 60-second cadence, pause for more than 60 seconds, resume, and verify that paused time is not written.
5. Stop, relaunch the app, and confirm the persisted state is restored or safely stopped according to the last action. During a provider interruption or revoked permission, confirm the notification/UI reports failure, confirmed steps do not advance, and the pending chunk retains the same client ID for retry.
6. On Android 15, inspect the foreground-service notification and logcat around service recreation and `dataSync` timeout handling. A timeout must persist a failure state and stop the service instead of silently claiming additional steps.
7. For long background runs, set the app battery usage to unrestricted on devices with aggressive vendor power management. Android can still interrupt work after a user force-stops the app or when its foreground-service policy limit is reached.

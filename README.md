# Fit Step Controller

Modern Android step-entry test app built with Kotlin, Jetpack Compose, and Health Connect.

## Features

- Mode 1: paced walking from 3 km/h to 12 km/h with a step target and automatic stop.
- Mode 2: direct step entry that writes a realistic non-zero time interval immediately.
- Health Connect read/write permission flow and exact app-record read-back verification.
- GitHub Actions APK build and release workflow for `v*` tags.

## Data Path

The app writes manually entered `StepsRecord` entries to Health Connect. Google Fit can display those records only when the user enables Google Fit's Health Connect sync on the Android device. The app treats its own exact Health Connect record as the write success boundary; Google Fit's displayed total is a separate, source-prioritized aggregate.

Health Connect and Google Fit do not treat displayed step count as a raw append-only counter. Both systems merge step data from multiple sources, avoid duplicate activity intervals, and may show a delayed or estimated total. To reduce avoidable loss during repeated manual writes, direct step entry stores each submission in a non-overlapping historical time window and reports both this app's raw records and Health Connect's aggregate for that interval.

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

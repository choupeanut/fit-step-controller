# Fit Step Controller

Modern Android step-entry test app built with Kotlin, Jetpack Compose, Google Sign-In, and Health Connect.

## Features

- Google Sign-In account linking for user identity.
- Mode 1: paced walking from 3 km/h to 12 km/h with a step target and automatic stop.
- Mode 2: direct step entry that writes a realistic non-zero time interval immediately.
- Health Connect read/write permission flow and aggregate read-back verification.
- GitHub Actions APK build and release workflow for `v*` tags.

## Data Path

The app writes `StepsRecord` entries to Health Connect. Google Fit can display those records only when the user enables Google Fit's Health Connect sync on the Android device.

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

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

## Google Sign-In OAuth

Debug and release builds use the same package name, `com.choupeanut.fitstepcontroller`, and the GitHub release APKs use a committed test signing key so OAuth setup is stable across test releases. Register this Android OAuth client:

- Package name: `com.choupeanut.fitstepcontroller`
- SHA-1: `C0:4B:CB:08:06:37:66:4F:3B:0F:7C:B8:6C:A2:EA:01:D4:2A:B8:75`

If sign-in fails, the app shows the Google Sign-In status code on screen and writes the same details to Logcat under `GoogleSignInManager`.

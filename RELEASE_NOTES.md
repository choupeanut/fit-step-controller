# v1.0.0

Initial Android release for device testing.

## Included

- Kotlin + Jetpack Compose Android app.
- Google Sign-In account linking with profile/email scope.
- Health Connect permission flow for reading and writing steps.
- Mode 1 paced walking at 3-12 km/h with a step target.
- Mode 2 direct step entry with immediate Health Connect write and aggregate read-back.
- Unit tests for step planning, chunking, completion, and aggregate behavior.
- GitHub Actions build for debug and release APK artifacts.

## Test APKs

- `app-debug.apk` is signed with the Android debug key and is the recommended APK for first device testing.
- `app-release-unsigned.apk` is produced for build verification only. It is not Play Store signed.

## OAuth Note

No `google-services.json`, Android OAuth client, Web OAuth client, or keystore was found in the original local folders. This release uses Google Sign-In basic profile/email linking without requesting a server auth code or ID token. Add a configured Android OAuth client later if server-side account verification becomes required.

## Health Connect Note

Google Fit display depends on the Android device syncing Google Fit with Health Connect. The app verifies Health Connect aggregate totals after writes; Google Fit dashboard display may lag or require enabling sync in Google Fit settings.

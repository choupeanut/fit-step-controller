# v1.0.1

Google Sign-In diagnostics release.

## Changed

- Debug APK now uses package `com.choupeanut.fitstepcontroller` instead of `com.choupeanut.fitstepcontroller.debug`.
- Debug and release APKs now use a stable test signing key for repeatable OAuth setup.
- Google Sign-In failures now show the concrete Google status code on screen and write details to Logcat.
- This makes OAuth package/SHA-1 misconfiguration visible instead of reporting every failure as cancellation.

## OAuth Reminder

If the app reports `DEVELOPER_ERROR (10)`, create or update an Android OAuth client with:

- Package: `com.choupeanut.fitstepcontroller`
- SHA-1: `C0:4B:CB:08:06:37:66:4F:3B:0F:7C:B8:6C:A2:EA:01:D4:2A:B8:75`

This committed key is for test APKs only and must not be used for Play Store production signing.

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
- `app-release.apk` is signed with the committed test key and is produced for device testing only. It is not Play Store production signed.

## OAuth Note

No `google-services.json`, Android OAuth client, Web OAuth client, or keystore was found in the original local folders. This release uses Google Sign-In basic profile/email linking without requesting a server auth code or ID token. Add a configured Android OAuth client later if server-side account verification becomes required.

## Health Connect Note

Google Fit display depends on the Android device syncing Google Fit with Health Connect. The app verifies Health Connect aggregate totals after writes; Google Fit dashboard display may lag or require enabling sync in Google Fit settings.

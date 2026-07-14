# v1.0.5

Cat-paw branding and resilient background pacing.

## Fixed

- Mode 1 now keeps the CPU awake with a bounded partial wake lock while its persistent `dataSync` foreground service is running, continues after the app task is removed, and restores a sticky session after service recreation.
- Launcher and round icons now use the friendly “活力步步” cat-paw mark.

## Limits

- Android battery managers, user force-stop, and the Android 15 `dataSync` foreground-service limit can still stop work; paced sessions remain capped at five hours.

# v1.0.4

Health Connect write verification and session recovery.

## Fixed

- Direct step entry writes a unique manual `StepsRecord` ending at the current time and verifies the exact app-origin record before reporting success.
- Repeated writes use stable client record IDs so retries do not duplicate a pending record.
- Paced walking persists pending chunks, retries provider failures, restores state after service recreation, excludes paused time, and reports failures instead of silently stopping.
- Android 15 `dataSync` timeout is handled safely and sessions are limited to five hours.

## Changed

- Google Sign-In was removed because it did not authorize Google Fit writes; Health Connect is now the explicit data boundary.
- Google Fit totals are documented as a separately synchronized, source-prioritized aggregate.

# v1.0.3

Step interval and walking progress fix.

## Fixed

- Direct step entry now allocates non-overlapping historical time windows. Repeated submissions no longer write 1,000 steps into the same recent minutes, which caused Health Connect and Google Fit aggregation to dedupe or normalize the apparent increase.
- Direct entry now reports both this app's raw step records and Health Connect aggregate totals for the written interval.
- Paced walking no longer writes a chunk immediately at start. It writes after each real elapsed interval, so paused time is not counted.

## Added

- Paced walking progress bar on the main screen.
- Paced walking Pause, Resume, and Stop controls on the main screen.

# v1.0.2

Foreground service and direct-entry feedback fix.

## Fixed

- Paced walking no longer starts a `health` foreground service. Android 14/15 require extra sensor or activity-recognition permissions for that type, so the service now uses `dataSync`, which matches periodic Health Connect writes.
- Direct step entry now immediately displays a writing state and shows the final success/error message inside the direct-entry card.

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

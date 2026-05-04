# Fit Step Controller v1.0.0 Design

## Objective

Build a modern Android app with Google account linking and two step-writing modes:

1. Paced walking at 3-12 km/h with a target step count and automatic stop.
2. Direct step entry that writes immediately without waiting for the walking duration.

## Technical Choice

The primary write path is Health Connect. Google Fit REST API is not used in v1.0.0 because new Fit API signups and OAuth scope approval are constrained by Google's deprecation path. Google Sign-In is used for account linking and user identity, while Health Connect controls health data permissions on-device.

## Architecture

- `StepPlanner`: pure domain logic for speed, stride, duration, warning, and interval calculations.
- `StepWriter`: abstraction for writing and reading step totals.
- `HealthConnectStepWriter`: production implementation backed by `StepsRecord`.
- `WalkingSessionController`: stateful domain controller used by the foreground service.
- `WalkingSessionService`: foreground service that writes periodic step chunks and stops at target.
- Compose UI: requests Google Sign-In, Health Connect permissions, and exposes both modes.

## Verification

Unit tests cover the pure planning logic, chunking behavior, automatic stop, and aggregate behavior through `FakeStepWriter`. GitHub Actions runs `testDebugUnitTest`, assembles debug and release APKs, and uploads artifacts.

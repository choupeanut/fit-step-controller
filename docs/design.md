# Fit Step Controller Reliability Design

## Objective

Build an Android Health Connect step-entry app with two step-writing modes:

1. Paced walking at 3-12 km/h with a target step count and automatic stop.
2. Direct step entry that writes immediately without waiting for the walking duration.

## Technical Choice

Health Connect is the only write target. Google Fit is treated as an optional consumer of Health Connect data; its displayed aggregate is not the app's write-success criterion. The deprecated Google Fit Fitness API is not used.

## Architecture

- `StepPlanner`: pure domain logic for speed, stride, duration, warning, and interval calculations.
- `StepWriter`: writes a stable client record ID and verifies the exact app-origin record.
- `HealthConnectStepWriter`: production implementation backed by `StepsRecord`.
- `WalkingSessionController`: durable state machine with pending chunks, retries, pause/resume, and a five-hour limit.
- `WalkingSessionService`: `dataSync` foreground service that writes verified 60-second chunks, remains active after the app task is removed, restores sticky sessions, and holds a bounded `PARTIAL_WAKE_LOCK` while running. It also handles Android 15 timeout.
- Compose UI: requests Health Connect permissions, restores persisted progress, and exposes both modes.

## Verification

Unit tests cover exact record verification, idempotent retries, durable pending chunks, pause/resume, failure states, automatic stop, aggregate diagnostics through `FakeStepWriter`, and the background wake-lock policy. The target is Android 9+, with manual Health Connect validation on Android 14/15. Android battery-management policies, user force-stop, and the Android 15 six-hour `dataSync` limit remain system-level constraints; paced sessions are capped at five hours.

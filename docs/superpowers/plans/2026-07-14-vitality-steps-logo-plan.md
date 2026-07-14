# Vitality Steps Logo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Fit Step Controller launcher icon with the approved “活力步步” adaptive icon while preserving all app behavior.

**Architecture:** Keep the existing manifest icon names and switch their resolution to a shared API 26+ adaptive icon. Store the mark as a vector foreground plus a solid background so Android can apply launcher masks without raster-specific cropping.

**Tech Stack:** Android resources, adaptive icons, vector drawable XML, Gradle Android resource packaging, Kotlin Android app.

## Global Constraints

- The icon must use the approved “活力步步” concept: two ascending cat paws, each with one pad and four toe beans, plus a blue motion dot.
- Colors are fixed to coral `#FF6B6B`, mint `#4FD1B5`, sky `#5BA7FF`, and warm cream `#FFF7F2`.
- No text, external assets, fonts, network calls, or runtime code changes are allowed for the icon.
- Preserve `android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher_round"`, application label, package name, and all Health Connect behavior.
- Android minSdk remains 28; adaptive resources must be available from API 26 onward.

### Task 1: Add the approved adaptive icon resource set

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

**Interfaces:**
- Consumes: the colors and geometry in `docs/superpowers/specs/2026-07-14-vitality-steps-logo-design.md`.
- Produces: resource names already referenced by `AndroidManifest.xml`.

- [ ] **Step 1: Define the vector foreground**

  Create a 108dp viewport vector with a centered, inset mark. Use two overlapping rounded footprint groups: coral lower-left, mint upper-right, and a sky-blue circular motion dot. Keep the outermost geometry inside approximately 18dp–90dp so launcher masks do not clip it.

- [ ] **Step 2: Define the adaptive background**

  Create a solid `#FFF7F2` drawable named `ic_launcher_background`.

- [ ] **Step 3: Wire both adaptive icon variants**

  Create both `ic_launcher.xml` and `ic_launcher_round.xml` under `mipmap-anydpi-v26`, each referencing the same foreground and background resources.

- [ ] **Step 4: Verify resource references**

  Run:

  ```bash
  ./gradlew :app:processDebugResources
  ```

  Expected: `BUILD SUCCESSFUL` with no missing adaptive icon or vector resource errors.

- [ ] **Step 5: Commit the resource-only change**

  ```bash
  git add app/src/main/res/drawable/ic_launcher_foreground.xml \
    app/src/main/res/drawable/ic_launcher_background.xml \
    app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
    app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
  git commit -m "feat: add vitality steps launcher icon"
  ```

### Task 2: Build and validate the branded APK

**Files:**
- Verify: `app/src/main/AndroidManifest.xml`
- Verify: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Consumes: the adaptive resources from Task 1 and the existing manifest icon names.
- Produces: an installable Debug APK with the new icon and unchanged application behavior.

- [ ] **Step 1: Confirm manifest identity is unchanged**

  Run:

  ```bash
  rg -n 'android:(icon|roundIcon)|applicationId|minSdk' \
    app/src/main/AndroidManifest.xml app/build.gradle.kts
  ```

  Expected: the manifest still uses `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, with applicationId `com.choupeanut.fitstepcontroller` and minSdk 28.

- [ ] **Step 2: Run the full relevant verification**

  ```bash
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
  ```

  Expected: all tasks finish with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect the packaged APK and record its checksum**

  ```bash
  ls -lh app/build/outputs/apk/debug/app-debug.apk
  sha256sum app/build/outputs/apk/debug/app-debug.apk
  git diff --check
  ```

  Expected: APK exists, checksum is printed, and diff check has no output.

- [ ] **Step 4: Commit documentation and final resource verification**

  ```bash
  git add docs/superpowers/specs/2026-07-14-vitality-steps-logo-design.md \
    docs/superpowers/plans/2026-07-14-vitality-steps-logo-plan.md
  git commit -m "docs: specify vitality steps logo implementation"
  ```

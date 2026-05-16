# AGENTS.md

## Project

- Name: `org-android`
- Type: Android app built with **Gradle Wrapper**
- Main module: `:app`
- Key build files:
  - `settings.gradle.kts`
  - `build.gradle.kts`
  - `app/build.gradle.kts`
  - `gradle/wrapper/gradle-wrapper.properties`

## Build Toolchain

- Gradle Wrapper: **8.13**
- Android Gradle Plugin: **8.11.2**
- Kotlin Android plugin: **1.9.22**
- Android module namespace: `com.orgutil`
- `compileSdk = 35`
- `minSdk = 26`
- `targetSdk = 34`

## Critical Environment Requirement

This project must be built with **Java 17+**.

On this machine, the default shell Java may point to Java 11, which is too old for AGP 8.11.2 and will fail with errors like:

- `Android Gradle plugin requires Java 17 to run`

Use Android Studio's bundled JBR instead:

```bash
export JAVA_HOME=/opt/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
```

Verify before building:

```bash
java -version
./gradlew -version
```

## Android SDK Requirement

This repo expects a valid `local.properties` with `sdk.dir=...` pointing to a working Android SDK.

If `local.properties` is missing or stale, Gradle build will fail before packaging.

## Canonical Build Commands

Run all commands from repo root:

```bash
cd /home/lzn/development/fast-development/tool/org-android-workspace/org-android
export JAVA_HOME=/opt/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
```

### Debug APK

```bash
./gradlew assembleDebug
```

Output:

- `app/build/outputs/apk/debug/app-debug.apk`

### Release APK

```bash
./gradlew assembleRelease
```

Output:

- `app/build/outputs/apk/release/app-release.apk`

### Install to Connected Device

```bash
./gradlew installDebug
./gradlew installRelease
```

### Clean Build

```bash
./gradlew clean
```

### Inspect Available Tasks

```bash
./gradlew tasks --all
```

## What Happens During Release Build

`assembleRelease` is not just a plain compile. It includes the normal Android packaging pipeline, including tasks such as:

- manifest processing
- resource processing
- Kotlin compilation
- kapt annotation processing
- Hilt / Room generated code compilation
- `minifyReleaseWithR8`
- `lintVitalRelease`
- APK packaging
- release signing validation

Release build is configured in `app/build.gradle.kts` with:

- `buildTypes.release.isMinifyEnabled = true`
- ProGuard/R8 rules from:
  - default optimize rules
  - `app/proguard-rules.pro`

## Signing

Release signing is configured in `app/build.gradle.kts` and uses a local keystore file:

- `app/my-release-key.keystore`

Do **not** print, copy, or newly hardcode signing secrets into docs, scripts, or commits.
If signing config needs cleanup, move secrets to local-only configuration or environment variables instead of keeping them in versioned build scripts.

## Verified Build Status On This Machine

The following commands were actually verified successfully with `JAVA_HOME=/opt/android-studio/jbr`:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Verified outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/mapping/release/mapping.txt`

## Known Warnings

Current builds may emit:

- `Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.`

This is currently a warning, not a blocker.

## Fast Troubleshooting

### Build fails immediately with Java version error

Cause:
- shell is using Java 11 or older

Fix:

```bash
export JAVA_HOME=/opt/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
```

### Build fails with SDK not found

Cause:
- `local.properties` missing or invalid

Fix:
- point `sdk.dir` to a valid Android SDK install

### Build succeeds but release APK missing

Check:

```bash
find app/build/outputs -maxdepth 4 -type f | sort
```

Expected release artifact:

- `app/build/outputs/apk/release/app-release.apk`

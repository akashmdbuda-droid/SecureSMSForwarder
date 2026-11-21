# versioning-and-updater Specification

## Purpose
Defines semantic version tracking, in-app changelog presentation, automated GitHub Release builds, and self-updating APK installation.

## Requirements

### Requirement: GitHub Releases update check and APK installation
The system SHALL check the latest GitHub Release on launch, compare semantic versions against `BuildConfig.VERSION_NAME`, and download and prompt installation of any newer signed APK asset.

#### Scenario: Newer GitHub release is available
- **WHEN** `AppUpdater.checkForUpdates()` finds a latest release tag whose numeric version tuple exceeds `BuildConfig.VERSION_NAME` and contains an `.apk` asset
- **THEN** `MainActivity` presents an Update Available dialog and, on confirmation, downloads `update.apk` via `DownloadManager` and launches `ACTION_VIEW` via `FileProvider`

### Requirement: Synchronized release metadata and in-app changelog
The system SHALL maintain synchronized version identifiers across Gradle build config, welcome toast, in-app changelog, git tags, and project context documentation.

#### Scenario: User launches an updated version for the first time
- **WHEN** `SettingsManager.lastSeenVersionCode < BuildConfig.VERSION_CODE`
- **THEN** `MainActivity` displays `ChangelogDialog` showing `ReleaseNote` entries from `Changelog.kt` and updates `lastSeenVersionCode`

### Requirement: Dynamic version display in welcome toast
The system SHALL construct the launch welcome toast version string dynamically from `BuildConfig.VERSION_NAME` so it cannot drift from `app/build.gradle.kts`.

#### Scenario: App launches on v1.1.0
- **WHEN** `MainActivity.onCreate()` executes on version `1.1.0`
- **THEN** the welcome toast displays `v1.1.0` automatically from `BuildConfig.VERSION_NAME`

### Requirement: Automated CI build and unit test verification
The system SHALL include a GitHub Actions CI workflow (`.github/workflows/ci.yml`) that runs `./gradlew testDebugUnitTest assembleDebug` on pushes and pull requests to verify Android compilation and unit tests automatically.

#### Scenario: Code is pushed to main or a pull request
- **WHEN** commits are pushed to `main` or opened in a pull request
- **THEN** GitHub Actions runs Java 17 + `./gradlew testDebugUnitTest assembleDebug` and reports pass/fail status

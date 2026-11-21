# Spec Delta

## ADDED Requirements

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

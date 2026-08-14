# Project Rules

## Release Process after Feature Additions
Whenever a new feature is added and confirmed as working, you MUST automatically follow this release process to deploy the update to users via GitHub Releases:

1. **Bump Version:** Increase the `versionName` and `versionCode` in `app/build.gradle.kts`.
2. **Update Welcome Toast:** Update the version string in the `Toast` message in `MainActivity.kt` (e.g., `Welcome to v1.0.X!`).
3. **Update Changelog:** Update the in-app changelog section (e.g., in `Changelog.kt`) with the new version details and a list of what changed.
4. **Commit:** Commit the changes (e.g., `git commit -am "Bump version to <new_version>"`).
5. **Tag Release:** Create a git tag matching the new version name, starting with 'v' (e.g., `git tag v1.1`).
6. **Push:** Push the commit and the tag to GitHub (e.g., `git push origin main` and `git push origin v1.1`).

Following these steps will trigger the `release.yml` GitHub Action, which builds the APK and publishes the auto-update to users.

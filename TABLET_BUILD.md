# Tablet build and preview

## Build the Android APK with GitHub Actions

1. Upload the repository with `app/`, `tablet-preview/`, Gradle files, and `.github/workflows/` at the root.
2. Open **Actions → Build debug APK → Run workflow**.
3. The workflow runs Android unit/migration tests, lint, browser tests, and packaged-permission verification.
4. Download `DailyCutReport-debug-apk` from the completed run and install `app-debug.apk`.

Android may request permission to install unknown apps. Enable it only for the installer used, then turn it off again.

## Use the browser preview

Open `tablet-preview/index.html` directly in a modern browser. It provides the redesigned Today, Foods, Settings, product catalog, per-date food logs, editing, and PNG export without a server.

The preview cannot access Health Connect or the native camera scanner. Those controls clearly direct users to the Android APK. Preview data stays in versioned `dcr_v2` localStorage, and its Content Security Policy blocks network connections.

## Offline verification

The source manifest removes dependency-added `INTERNET` and `ACCESS_NETWORK_STATE` permissions. The workflow does not publish an APK unless `verifyOfflineDebugApk` confirms that both permissions are absent from the packaged artifact.

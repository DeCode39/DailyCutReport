# Tablet-only build route

This project is Android source code. Since you are on a tablet and do not have Android Studio, use GitHub Actions to compile the APK.

The installed app still has **no internet permission**. The build server uses internet only to download Android/Gradle dependencies and produce the APK.

## Steps from a tablet

1. Download and unzip `DailyCutReport_tablet_build.zip`.
2. On GitHub, create a new private repository, for example `DailyCutReport`.
3. Upload the project files to the repository root. Make sure these files/folders are at the top level:
   - `app/`
   - `.github/workflows/build-debug-apk.yml`
   - `build.gradle.kts`
   - `settings.gradle.kts`
   - `gradle.properties`
4. Open the repository on GitHub.
5. Go to **Actions**.
6. Open **Build debug APK**.
7. Tap **Run workflow**.
8. After it finishes, open the completed workflow run.
9. Download the artifact named `DailyCutReport-debug-apk`.
10. Unzip the artifact on your tablet.
11. Install the `app-debug.apk` file.

## Android install note

Android may ask you to allow installing unknown apps from your browser or file manager. Allow it only for this install, then disable it again afterward.

## App network policy

The Android manifest intentionally does not include:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

So the installed APK should not have network socket access.

## If GitHub upload from mobile is annoying

Use the included `tablet-preview/index.html` for a manual-only preview. It runs in the browser without internet and can export the daily report as a PNG, but it cannot access Health Connect. Health Connect requires the native Android APK.

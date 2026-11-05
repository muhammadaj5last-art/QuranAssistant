# مساعد القرآن · Quran Assistant

An Arabic-first Android voice assistant for Quran recitation, built with Kotlin, Jetpack Compose, sherpa-onnx and SQLite.

Developed by [Mohammed Aljohar](https://github.com/muhammadaj5last-art).

## Features

- Offline speech recognition with local Arabic and English model support; the hands-free interface uses Arabic.
- Arabic activation phrase: **يا مساعد القرآن**.
- Persistent command mode with a 30-minute inactivity timeout and spoken sleep commands.
- Voice playback commands and distinct feedback tones.
- Searchable catalog of all 114 Surahs.
- Mishary Alafasy recording downloads, local playback and audio imports.
- SQLite metadata for the local audio library.

## Example interaction

Say **يا مساعد القرآن**, then **شغل سورة الفاتحة**. After activation, issue further commands without repeating the wake phrase. Say **طفي** to return to wake listening.

Microphone recognition pauses during recitation to avoid interpreting speaker audio as commands. Pause playback on screen before giving another voice command. Hands-free operation is foreground-only; this version does not implement locked-screen wake activation.

## Build and run

This source package preserves the uploaded working project's configuration and Kotlin files. Large external assets are intentionally excluded. **Restore the files in [SETUP.md](SETUP.md) before building.** In particular, three bundled recordings are referenced directly by Android resource IDs.

Open the project in Android Studio, install the SDK requested by the project, restore local dependencies and assets, and sync Gradle. Run on a physical Android device and grant microphone permission.

The supplied configuration declares Android SDK 37, minimum SDK 26, Android Gradle Plugin 9.5.0, Gradle 9.6.0 and a Gradle daemon Java toolchain of 25. These values were preserved from the owner's files; a clean build has not yet been verified here.

On Windows, build a debug APK with `gradlew.bat assembleDebug`; on macOS/Linux use `./gradlew assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`.

For public distribution, generate a signed release APK in Android Studio and keep the signing key private. APK distribution is planned through GitHub Releases; no release APK is included yet.

## Project structure

| Package | Responsibility |
| --- | --- |
| `audio` | Playback, imports and recording downloads |
| `data` | Surah catalog and SQLite metadata |
| `voice` | Recording, speech recognition, phrase parsing and feedback |
| `ui/screens` | Main assistant interface and diagnostic screens |
| `ui/theme` | Compose theme |

## Offline behavior and privacy

Speech recognition uses local models. Missing recitations require internet access for their first download; saved recordings can then play offline. The app requests microphone and internet permissions. Android backup is enabled in the supplied manifest; this is not a promise that app data never leaves the device.

## Status

The owner reports the app working on a Samsung S24 Ultra. This repository preparation has not included a full Android build or independent device test. The expanded parser supplied by the owner is preserved unchanged.

## Third-party assets and licensing

See [SETUP.md](SETUP.md) for excluded assets. The uploaded speech binary has not been independently matched to an official release and no license notice was found inside its archive. Model and recording redistribution terms still need verification before bundling them in a public APK. No open-source license has been selected for this project's original code yet.

# Parts of Speech Dictionary

A native Android starter project using Kotlin, Jetpack Compose, Room, and GitHub Actions.

## Features in this starter
- Compose home screen with word search and category browsing
- Room database for offline saved entries
- Add/edit dictionary entries manually
- Multiple parts of speech per entry
- JSON export/import foundation
- GitHub Actions workflow to build a debug APK
- Taxonomy seed based on `PARTS OF SPEECH.pdf`

## Requirements
- Android Studio with JDK 17, or a compatible Gradle/Android SDK environment
- Android SDK Platform 35
- For GitHub Actions, push this project to a repository and run the `Build Android APK` workflow.

## Build locally
```bash
./gradlew assembleDebug
```
The APK should be at `app/build/outputs/apk/debug/app-debug.apk`.

## Important notes
- This starter does not claim an APK has already been built or tested in this environment.
- Online dictionary lookup is left behind a repository interface so the API can be integrated without fabricating definitions. Add network lookup and licensing/source attribution before relying on external dictionary content.
- The included grammar taxonomy is a starting list based on the provided PDF, not an assertion that every English grammar framework classifies every term identically.

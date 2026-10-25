# Phone Wake — rebuilt project

A minimal Android app using Vosk for local/offline speech recognition. It has no MP3 files or spoken responses.

## Commands
- **Phone**: one vibration, then a 3-second command window. If no command is heard, it vibrates again.
- **Flashlight** within the window: toggles the torch and vibrates twice.
- **Phone Locate**, or **Phone** then **Locate**: plays three short beeps, 3 seconds apart.

## Build using GitHub Actions
1. Extract this ZIP.
2. Upload/replace the project files in your `PhoneWake` GitHub repository.
3. Commit to `main` (or `master`).
4. Open **Actions → Build Phone Wake APK** and wait for it to finish.
5. Download the `PhoneWake-APK` artifact from the completed run, extract it, and install `app-debug.apk`.

The workflow downloads the English Vosk model during the build. The model is used locally on the phone after installation; no cloud speech service is used.

## Test in this order
1. Open the app and tap **Test vibration**. If it does not vibrate, check phone vibration settings and test another app; speech recognition is not involved in this test.
2. Tap **Start listening** and grant microphone/notification permissions.
3. Keep the persistent Phone Wake notification visible. On Samsung, set Settings → Apps → Phone Wake → Battery to **Unrestricted** if background listening is stopped.

The Locate command is a sound confirmation, not GPS/device tracking. Flashlight toggling requires the camera flash to be available to Android.

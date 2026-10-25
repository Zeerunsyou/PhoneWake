# Phone Wake

An Android app for Android 8.0+ with offline English speech recognition via Vosk.

## Voice commands

1. Open the app and tap **Start listening**. Grant microphone permission and camera permission if prompted.
2. Say **“Phone”**. The phone vibrates once and opens a 3-second command window.
3. Say **“flashlight”** during that window to toggle the torch. The phone vibrates twice for the command.
4. Say **“location”** during that window to play the default notification sound three times, 3 seconds apart. This command does not retrieve or share GPS location.
5. If no command is recognized within 3 seconds, the phone vibrates once to signal that the window closed.

You can also tap **Test vibration** to check vibration independently. Tap **Stop listening** to stop the service.

## Build without installing Android Studio locally

The included GitHub Actions workflow downloads the Vosk small English model and builds the APK in the cloud whenever you push to `main` (or run the workflow manually).

1. Put the project files in the root of your GitHub repository.
2. Open **Actions** and select **Build Phone Wake APK**.
3. Open the successful run and download the **PhoneWake-APK** artifact. Extract it to get `app-debug.apk`.

## Build with Android Studio

Open this folder as a project. Use JDK 17 and run `gradle assembleDebug` (or use Android Studio's Build APK action). The Vosk model ZIP must be present at `app/src/main/assets/model-en-us.zip`; the GitHub Actions workflow downloads it automatically.

## Notes

- Offline recognition begins after the Vosk model is unpacked on first start; loading may take a little time.
- Android may stop microphone foreground services due to battery/background restrictions. Keep the ongoing Phone Wake notification visible.
- The “location” command only plays sounds; it does not access GPS.

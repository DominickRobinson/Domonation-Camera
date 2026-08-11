# Paper Camera development guidance

## Project priorities

- Keep the interface minimal, high-contrast, and friendly to e-ink displays.
- Reuse the existing Material Symbols-derived icon assets instead of drawing new icons.
- Keep the header and bottom control bars fixed when the phone rotates; rotate only their icons and labels.
- Keep the camera preview in the fixed activity coordinate system while applying device orientation to saved photos and videos.
- Tap-to-focus must remain silent: never show a focusing text popup.

## Working conventions

- Build changes with `./gradlew assembleDebug`.
- When an Android device is connected, install completed changes with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and launch `com.domonation.camera/.MainActivity`.
- Verify UI changes on the connected device when practical, including Back-button behavior and both physical orientations.
- Do not commit generated Gradle state, APK build directories, IDE files, or device-test captures.

## Technology

- Native Android app written in Java.
- Gradle Kotlin build scripts.
- CameraX with Camera2 interop for EXIF thumbnail requests.
- Minimum Android SDK 26; target SDK 36.

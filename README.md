# Domonation Camera

A deliberately small, e-ink-friendly Android camera. It captures JPEG photographs and can explicitly request an embedded EXIF thumbnail from the Android camera hardware for devices such as the Canon SELPHY CP1500.

## Design

- High-contrast, mostly static interface
- No animated transitions
- One shutter button
- Optional embedded EXIF thumbnail
- Verifies the saved JPEG and reports whether a thumbnail is present
- Photo and video modes
- Timelapse mode that saves both JPEG frames and an H.264 MP4
- Preset or custom timelapse capture interval and playback FPS
- Header timer cycles through off, 3 seconds, and 10 seconds
- Optional volume-button shutter control, enabled by default
- Off, auto, and on flash control
- Tap-to-focus, zoom, and exposure compensation
- Toggleable zoom/exposure panels replace the shutter controls only while adjusting
- Ticked zoom and exposure sliders use taller markers for the 1× and +0 defaults
- The viewfinder remains fixed while saved photo/video orientation follows the device
- Header and bottom bars remain fixed while their icons and labels rotate in place
- Optional review before save
- User-selectable save folder, with `Pictures/DomonationCamera` as the default
- Tabbed General, Photo, Video, and Timelapse settings with radio choices

## Build

Open the directory in Android Studio and run the `app` configuration, or run:

```sh
./gradlew assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions also builds the APK on every push and pull request. Pushes to
`main` create or update the **Domonation Camera latest** release and attach
`Domonation-Camera-latest.apk`; other branches and pull requests expose their APKs
as 30-day workflow artifacts.

## Device behavior

The app selects the largest nonzero thumbnail size up to 512×512 reported by the rear camera and requests it through Camera2. Some vendor camera implementations may ignore that request; the status line after each capture verifies the actual output instead of assuming success.

Timelapse saves every JPEG frame first, then creates an H.264 MP4 at the selected playback FPS. Encoding runs after capture stops; if video encoding fails, the original JPEG sequence remains saved.

# K.I.N.D. Radio — integrated HOME + Genesis + Cast (source candidate)

The project combines the user-tested HOME v0.2 Cast screen's native transport with the Genesis HTML queue interface. MainActivity owns **one** queue and controls either local MediaPlayer or a Cast receiver; a WebView uses a small, intentionally limited JavaScript bridge to call that controller. An Android document picker grants access to multiple selected local audio files. The existing CastProvider remains unchanged. The local AudioServer now supports Content-Length and basic open-ended Range requests when the document provider gives a file size.

## Build

This folder is a Gradle Android project (AGP 8.7.3; Java 17; compileSdk 35). Open in Android Studio, or build with `gradle :app:assembleDebug` using an Android SDK and Gradle 8.9+. The included GitHub Actions workflow builds a debug APK after pushing to GitHub. **No APK was produced in the current environment**, which lacks the Android SDK/Gradle; the code is a build candidate, not a validated installation.

## Intended test sequence

1. Install debug build and open K.I.N.D. HOME; select multiple MP3s using Add audio files.
2. With no Cast connection, play, pause, seek, skip and stop locally. Confirm the queue matches the LCD display.
3. Connect Vizio using the native Cast route icon in the top toolbar. Choose a queue item and play; pause, skip and stop on Vizio.
4. Disconnect Cast and test phone playback again. Repeat on a second normal home/guest Wi-Fi network; verify phone and receiver can reach each other.

## Limitations

- Cannot confirm device compatibility until built and tested; some content providers may not expose sizes or permit repeatable reads.
- For remote playback, the HTTP stream is reachable by devices on the same network; do not use on untrusted networks without a future authorization design.
- The Cast receiver's progress and state are polled; casting/reconnection transitions may need adjustments after device testing.
- Five customizable HOME favorites, detailed DJ overlay, Suno embedded library, and cross-protocol discovery are **not** in this integration.
- This refactors the tested HOME screen into a combined WebView + native Cast toolbar; original ZIPs remain untouched.

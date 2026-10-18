# Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Experimental native touchscreen passthrough

In **Settings > Input settings**, enable **Native touchscreen passthrough** and
start a new stream to a Sunshine host advertising native pen/touch support.
The setting defaults to **OFF**. With it off, upstream trackpad/absolute-mouse
behavior and gestures remain unchanged.

When enabled, finger contacts use the existing
`NvConnection.sendTouchEvent()` -> `MoonBridge.sendTouchEvent()` ->
`LiSendTouchEvent()` path instead of mouse emulation. This takes precedence over
the touchscreen-trackpad setting without changing that saved preference.
The three-finger keyboard shortcut is bypassed so three or more contacts can
reach the host. Physical mouse, keyboard, controller, and existing stylus routing
are retained. On-screen controller buttons retain their existing local handling.

Contact IDs come from Android pointer IDs, never pointer indexes. MOVE sends one
packet for each active contact; DOWN/UP target the action-index pointer.
ACTION_CANCEL sends CANCEL_ALL, and FLAG_CANCELED on UP cancels that contact.
Focus loss, pause, and releasing input capture cancel active native contacts.
Coordinates are clamped/normalized to the actual aspect-fitted StreamView
(0..1), including its offset when events arrive through the background view.
Pressure is normalized/clamped to 0..1. Contact ellipse axes are normalized to the
video view using the existing native-input projection convention; unavailable
pressure/contact area use zero, and unavailable rotation uses LI_ROT_UNKNOWN.

The pinned native protocol carries one contact per packet with an opaque 32-bit
ID. It has no client-side fixed contact-count limit, and this implementation
does not use the legacy two-touch context array. Actual simultaneous-contact
limits depend on Android hardware and the host. Sunshine native touch support
must be advertised (LI_FF_PEN_TOUCH_EVENTS); mouse events cannot provide an
equivalent fallback. Nonzero native send results are logged and shown once;
native sending stops for that stream, with one best-effort CANCEL_ALL for
existing contacts. Events stay consumed rather than producing duplicate mouse
input. Reconnect after fixing the host or turn the setting off for mouse mode.
A successful send means the native library queued the packet, not that the host
injected a contact. State changes are sent reliably by the existing protocol;
MOVE packets may be dropped under congestion.

Host-side translation tests require only installed Python 3 and a JDK capable of
compiling Java 17. No downloads, SDK, emulator, JNI loading, or Gradle are needed:

```sh
python3 tests/run_native_touch_tests.py
```

The tests compile the real MoonBridge constants and plain-Java interfaces,
verify constants against the pinned native header and the preference default,
and exercise contact identity/state, normalization, cancellation, and failures.
They do not run Android framework MotionEvents or prove host injection.
Generated classes and temporary data stay under the repository's disk-backed
`build/native-touch-tests/`, never `/tmp` or tmpfs. Initialize the repository's
existing native submodules before running the protocol checks.

APK compilation remains the existing GitHub Actions debug workflow using
`:app:assembleNonRootDebug`. No local touch injection, host-side forwarding, or
other application/runtime integration is part of this experiment.

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).

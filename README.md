# usbDacVolumeAndroid

Sets the hardware volume of a USB-C headphone adapter (DAC) on Android. No root needed.

## Why

Some USB-C to 3.5 mm adapters start up at a low hardware volume. The Apple adapter, for
example, starts at −20 dB out of a −60…0 dB range. Windows, macOS, Linux and iOS either raise
the adapter's volume or control it directly. Android does neither, so the adapter stays quieter
than it can be. This app sets it to the level you choose, 0 dB (the maximum) by default.

## About this fork

This is a fork of [polhdez/usbDacVolumeAndroid](https://github.com/polhdez/usbDacVolumeAndroid),
which added auto-apply and auto-exit to the
[original app](https://github.com/guyman624/usbDacVolumeAndroid). It fixes two problems seen
with the Apple adapter on a Galaxy S24 Ultra:

- **Endless relaunch loop as soon as headphones were plugged in.** The app reset the USB device
  after setting the volume. The reset made the adapter disconnect and reconnect, which
  relaunched the app, which reset it again. The libusb code also crashed on the adapter's
  USB Audio Class 3 configuration. The app no longer resets the device or uses libusb.
- **Music went silent after the volume was set** until it was paused and resumed by hand. The
  app now pauses playback for about 3 seconds, sets the volume, and lets playback resume.

It also has a new Material 3 interface with a volume slider in dB instead of a hex field.

There is no prebuilt APK for this fork yet; see [Building](#building). The APK under upstream's
releases still has the relaunch loop.

## Usage

1. Open **USB DAC Volume** once. If your headset has a microphone, tap **Allow** in the
   Microphone permission card (see [Microphone permission](#microphone-permission)).
2. Plug in the adapter with headphones connected. When Android asks which app should open the
   device, choose this one and tick "Always".
3. Leave the **Hardware volume** slider at 0 dB (the maximum on most adapters) and tap
   **Apply**. Music pauses for about 3 seconds, then resumes louder. The card at the top shows
   the result, for example "Set to 0.0 dB (was −20.0 dB)".

Options:

- **Apply when connected** sets the volume each time Android opens the app because the adapter
  was plugged in. Opening the app yourself does not apply anything, so it never pauses your
  music unasked.
- **Close after applying** closes the app once the volume is set.

The adapter forgets the setting every time it reconnects, which includes plugging the
headphones into it. Turn on both options to have it applied automatically each time.

The slider covers −60 to 0 dB until the first apply, then follows the range the adapter
reports. A volume saved by an older version (as a hex value) is converted to dB.

### Microphone permission

Android only lets an app open a USB audio device that can record (for example a headset with a
mic) if the app has microphone permission. The app never records.

## How it works

The app sends a USB Audio Class volume request (feature unit 2, channels 1 and 2) to the
adapter's audio control interface. Linux only accepts that request from the app while the app
owns the interface, so the app:

1. on plug-in, waits until Android has finished connecting the adapter as an audio output,
2. asks other apps to pause (transient audio focus) and waits for 3 seconds of silence,
3. takes the audio interfaces from Android's audio driver, sets the volume and reads it back,
4. hands the interfaces back, so Android recreates the sound card,
5. releases audio focus, so the music app resumes.

Steps 1 and 2 matter on phones that route USB audio through the audio DSP (Qualcomm USB audio
offload, as on the Galaxy S24 Ultra). There, a stream that keeps playing while the sound card is
recreated goes silent without any error until it is restarted.

## Limitations

- The feature unit ID is fixed at 2. That is right for the Apple adapter and for adapters that
  use the USB Audio Class 3 headphone profile; other adapters may use another ID.
- Playback pauses for about 3 seconds each time the volume is set. Without root there is no way
  around it: the adapter rejects volume requests that are not sent to its audio interface, and
  Android only allows sending them after taking that interface from the audio driver.
- An app that ignores audio focus keeps playing. If the sound stops after the volume is set,
  pause and resume it.

## Tested devices

| Phone | Android | Adapter | Result |
|-------|---------|---------|--------|
| Galaxy S24 Ultra (SM-S928N) | 17 | Apple USB-C to 3.5 mm | Working (this fork) |

Reported for upstream versions: OnePlus 12R (Android 14), OnePlus 7T (Android 10/12),
Pixel 3 (Android 11) and Galaxy A34 (Android 14) working; Huawei P20 (Android 10) not working.

## Building

Requirements: JDK 17 or newer, and the Android SDK with platform 36 and build-tools 36.0.0. The
Gradle wrapper downloads Gradle 9.6; the Android Gradle Plugin is 9.4. No NDK is needed.

```sh
sh ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app logs what it does under the `UsbDacVolume` tag (`adb logcat -s UsbDacVolume`).

## Credits

- [guyman624](https://github.com/guyman624/usbDacVolumeAndroid): the original app
- [polhdez](https://github.com/polhdez/usbDacVolumeAndroid): auto-apply, auto-exit and
  permission handling
- [ibaiGorordo](https://github.com/ibaiGorordo/libusbAndroidTest): the libusb sample the
  original app was built on

# 📺 TV Companion

![License](https://img.shields.io/badge/License-MIT-green)
![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%2B%20Android-blue)
![Language](https://img.shields.io/badge/Language-Java-orange)
![Status](https://img.shields.io/badge/Status-Working-lightgreen)

TV Companion is a two-app system that enables rich remote control of Android TV devices from an Android phone, including URL launching, volume control, and live screen streaming over LAN.

## 🤔 Why TV Companion?

The built-in `Google TV` app provides only a digital replica of an Android TV remote. I wanted an app that could do more, like launch URLs and stream the TV screen to my mobile phone directly.

## ▶️ Demo

Video to be inserted.

## How does it work?

TV Companion consists of two Android Apps:

1. 📺 **TV Companion (Host App)**: \
This app is installed on the Android TV and is responsible for managing and executing all the commands received from the controller app.
2. 📱 **TV Companion Controller**: \
This app is installed on the Android device that shall control the Android TV.

Note: The Host app automatically starts up when the Android TV boots up.

<details>
<summary>⚙️ <b>Technical Details (for nerds)</b></summary>

- The controller app uses NSD (Network Service Discovery) service to discover the host app running on any nearby Android TV over the same network connection. (Currently there is no support for controlling multiple hosts).

- The Host app has a background service that is constantly running called `CompanionService`.

- Once discovered, the Controller app and Host app communicate using TCP, where the controller app sends a command, then the host app responds with acknowledgement.

- The Host app is setup to listen to the `android.intent.action.BOOT_COMPLETED` action to start the app automatically when the Android TV boots up.

- Streaming the Screen:
  - The Host app requests the permission to capture the screen only, the user is responsible for enabling the 'don’t ask again' option which makes sure the host app doesn't have to repeatedly request permission to capture the screen.
  - The Host app uses the `MediaProjection` API to capture the screen.
  - When the Controller sends the command to start streaming the Host's screen, it starts another service to start streaming the screen using RTP over UDP.
  - The controller app receives this stream and displays it to the user on a `SurfaceView` widget.
  - As per the current implementation, the stop stream command shall be sent before closing the controller app to make sure proper cleanup of streaming resources takes place.

</details>

## 📱 How to use?

1. **Install Host App**: Sideload and install the `TVCompanion` app on your Android TV.
2. **Grant Permissions**: Launch the app on your TV. You will be prompted to allow screen capture. Select "Start now" (and check "Don't ask again" for a smoother experience).
3. **Install Controller**: Install the `TVCompanionController` app on your Android Phone.
4. **Connect**: Ensure both devices are connected to the **same Wi-Fi network**.
5. **Control**: Open the Controller app. It will automatically scan for your TV. Tap the device name to connect.
   - Use the D-Pad for navigation.
   - The TV screen will automatically mirror to your phone.
   - Use the "Launch URL" feature to open websites directly on the TV.

## 🛠️ How to build?

The repo contains two folders (one for each app). They're nothing but Android Studio projects which can easily be compiled and packaged using Android Studio.

Development Requirements:

- Android Compile SDK Version: `36`
- Android Min SDK Version: `28` (TVCompanion) / `24` (TVCompanionController)
- JDK: `JDK 11 or higher`
- Android Gradle Plugin: `8.13.2`
- Gradle: `8.13`

TV Companion supports almost all Android TVs out there.
But still remains untested on devices like `Amazon Fire TV`.

## ⚖️ LICENSE

TV Companion is licensed under the MIT License. \
**© Avnish Kirnalli 2026.**

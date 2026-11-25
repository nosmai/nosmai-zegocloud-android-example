# Nosmai Effects with ZEGOCLOUD on Android

Publish a camera with Nosmai beauty effects from a native Kotlin app and watch the processed video on a second Android device. The host previews the same effects locally.

## Requirements

- Android Studio, JDK 17 and Android SDK Platform 35.
- Two ARM Android devices running API 24 or later for a host and viewer session.
- A Nosmai Effects licence for the host application ID, `com.nosmai.quickstart.zegocloud`.
- The project pins Nosmai Android SDK 3.0.7 and ZEGO Express 3.21.0. Gradle downloads both dependencies.

## Configure and build

From the project root, create the local settings file.

```sh
cp app/src/main/assets/nosmai.example.properties app/src/main/assets/nosmai.properties
```

Set `nosmaiLicenseKey` for the host in the new file. A viewer-only installation can leave it empty. If you change the application ID, use a licence registered for the new ID. Gradle packages this file in the APK, so use it only for client-side settings.

Set `appID`, the project’s client `appSign`, and the same `roomID` on both devices. The example uses AppSign authentication. For a token-authenticated application, retain your existing server-issued room token flow.

Open the project in Android Studio, or build the debug APK from the command line.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Start a session

1. Install the app on both phones. Choose **Go Live (Host)** on the broadcaster and grant camera and microphone access.
2. Choose **Watch (Audience)** on the second phone and join the same room or session.
3. Apply a visible effect while moving in front of the host camera. On the viewer, check the effect, motion, orientation and microphone audio.

## Connect an existing app

The sample passes Camera2 YUV frames to `NosmaiPreviewView.processYuvFrame`. With `NosmaiSDK.RenderMode.DUAL_OUTPUT`, the host gets a local preview and processed I420 frames through `NosmaiSDK.setFrameCallback`. The streaming SDK carries the microphone audio.

Enable raw custom capture on the main publishing channel before room login. Start forwarding frames when ZEGO requests capture. For each callback, send the I420 buffer, dimensions, strides and timestamp through `sendCustomVideoCaptureRawData`. The viewer plays the stream announced by the room.

On leaving, stop forwarding frames and detach the Nosmai callback before releasing the sender or video source. See the [host and viewer implementation](app/src/main/java/com/nosmai/quickstart/zegocloud/LiveActivity.kt) for setup, frame ownership and teardown.

## Troubleshooting

If login fails, check the project authentication mode and client AppSign. If the viewer joins but sees no video, confirm both devices used the same room and the publisher started on the main channel.

## References

- [Nosmai Effects for Android](https://nosmai.com/docs/effects/installation/android/)
- [ZEGO Express Android API](https://www.zegocloud.com/docs/live-streaming-android/client-sdk/api/class/zegoexpressengine)

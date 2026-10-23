# Changelog

## 0.1.0 — build 5

- Redesign the chat composer as one rounded row: image picker, multiline editor, send, and the existing stop control.
- Remove the duplicate navigation-bar inset below the composer and reduce its outer vertical padding to 4 dp. Preserve IME padding for the keyboard.
- Consume the padding supplied by the main Scaffold before child IME padding, so keyboard spacing accounts for the already reserved bottom navigation height.
- Overlay the animated “Back to latest” button on the transcript instead of reserving a row above the composer. Tapping scrolls immediately and resumes tail following.
- Scroll to the actual end of an oversized final transcript item, including the bottom content padding. Manual scrolling detaches following until the user returns to the bottom or taps the button.
- Increment `app/build.gradle.kts` `versionCode` for each APK build, not each source edit; the distributed APK is build 5.

Verification: the final build 5 APK and the 98-test JVM regression suite passed in Kubernetes; APK version, packaged UI strings, ZIP integrity, and SHA-256 were checked. The 10 Android storage/image/draft instrumented tests passed earlier in this session on build 3 / API 30, not on the final build 5 APK. Visual and changed-screen interaction checks remain unverified: the rendering-enabled software emulator repeatedly restarted Android's `system_server`; no physical Android device was available.

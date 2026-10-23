# Changelog

## 0.1.0 — build 12

- Make both composer/editor containers transparent while preserving keyboard insets and readable controls.
- Keep Control-session drafts editable while connecting, syncing, reconnecting, or waiting for a network. Send accepts unsent prompts into a session-bound FIFO, with a visible pending count.
- Dispatch pending prompts only after the writable handshake and complete snapshot. Keep text/images across same-credential recovery; cancel with notice on disconnect, forget, terminal failure, or credential changes.
- Bound the in-memory outbox to 16 prompts / 16 MiB encoded payload. Rejected submissions keep their drafts; completion cannot erase newer edits or another pairing's draft.
- Never replay a socket-accepted or uncertain prompt. Local pending acceptance is not host acknowledgment. Interrupts, approvals, and agent commands remain online-only.
- Keep the selected session and network observation running behind an ongoing foreground-service notification, with Open and Disconnect actions. Use Android's `remoteMessaging` service type and request notification permission where applicable.
- Isolate stale terminal events from newer selections, cancel obsolete restore work, and preserve rapid disconnect/retry demand while the previous service stops.
- Preserve the pinned debug signer and package identity; increment the APK version to 12.

Verification: build 12 app/test APKs succeeded in Kubernetes; 105 JVM tests passed, and all 13 Android instrumented tests passed against build 12 on API 30. The runtime scenario exercised background reconnect, snapshot-gated FIFO delivery, no replay, notification Disconnect, explicit retry, and immediate disconnect/retry with service recreation. APK signer, remote/local digest equality, checksum, and ZIP integrity passed. Evidence: `evidence/background-queue/`.

Limits: queued prompts are memory-only and disappear with process death. Android force-stop, battery/Doze policy, or OEM restrictions can stop/defer background operation. Physical-device visuals, API 34+ foreground-service policy, real Wi-Fi/mobile handoff, and sticky process-kill restoration were not exercised; the API 30 ATD emulator disables drawing. The runtime host was an encrypted wire-3 fixture, not a real OMP/model-provider authorization test.

## 0.1.0 — build 7

- Fix installation conflicts caused by disposable Kubernetes builders generating a different debug signing key for each APK.
- Distribute the debug app as `dev.omp.mobile.debug`, labeled **OMP Mobile Debug**, so it installs alongside the legacy `dev.omp.mobile` app without uninstalling it. Pair again in the new app; legacy app data is not migrated or removed.
- Persist the new signing key in the immutable `debug-keystore` Secret in namespace `omp-mobile-signing`, with a private backup at `~/.config/omp-mobile/signing/debug.keystore`. Do not delete that namespace during build cleanup or regenerate its key.
- Pin the public signing-certificate fingerprint in `signing/debug-certificate.sha256`. Gradle rejects missing or unexpected signing keys instead of generating a replacement.
- Use `bash scripts/build-debug-kube.sh` for builds. It mounts the persistent key, checks the packaged signer and application ID, updates the APK/checksum, and cleans up only the disposable build namespace. Private keystores are ignored by Git and excluded from source archives.

Verification: build 7 and 98 JVM tests passed in Kubernetes. Missing-key and wrong-key negative configuration checks passed. A fresh verifier pod recovered the same pinned signer from the persistent Secret. On an isolated Android API 30 emulator, both packages installed together; reinstalling the new APK succeeded, and private test-data markers in both packages remained intact.

## 0.1.0 — build 5

- Redesign the chat composer as one rounded row: image picker, multiline editor, send, and the existing stop control.
- Remove the duplicate navigation-bar inset below the composer and reduce its outer vertical padding to 4 dp. Preserve IME padding for the keyboard.
- Consume the padding supplied by the main Scaffold before child IME padding, so keyboard spacing accounts for the already reserved bottom navigation height.
- Overlay the animated “Back to latest” button on the transcript instead of reserving a row above the composer. Tapping scrolls immediately and resumes tail following.
- Scroll to the actual end of an oversized final transcript item, including the bottom content padding. Manual scrolling detaches following until the user returns to the bottom or taps the button.
- Increment `app/build.gradle.kts` `versionCode` for each APK build, not each source edit; the distributed APK is build 5.

Verification: the final build 5 APK and the 98-test JVM regression suite passed in Kubernetes; APK version, packaged UI strings, ZIP integrity, and SHA-256 were checked. The 10 Android storage/image/draft instrumented tests passed earlier in this session on build 3 / API 30, not on the final build 5 APK. Visual and changed-screen interaction checks remain unverified: the rendering-enabled software emulator repeatedly restarted Android's `system_server`; no physical Android device was available.

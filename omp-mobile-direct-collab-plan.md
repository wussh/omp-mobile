# OMP Mobile — Direct Collab Android Native

## Context
Bangun aplikasi Android native Kotlin + Jetpack Compose untuk Phase1–2: QR/link pairing, transcript dan control agent, kredensial aman, serta reconnect/resync. OMP/model tetap berjalan di host; aplikasi memakai protokol `/collab` resmi tanpa backend tambahan. Koneksi hanya foreground dan dipulihkan saat dibuka kembali; transcript mengikuti jurnal kronologis client web `/collab`, bukan rekonstruksi cabang aktif TUI. Gateway/discovery room baru tidak dibuat: room pengganti memerlukan link baru.

## Findings terverifikasi
- Root `/home/wush/playground/omp-mobile` kosong; tidak ada implementasi/build/pola lokal untuk digunakan ulang. Semua file target pada langkah berikut adalah file baru.
- `bun`, `omp`, `git`, `cargo`, dan `rustc` tersedia di PATH; `java`, `javac`, `gradle`, `adb`, `sdkmanager`, `android`, dan `clang` tidak ditemukan di PATH. JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT belum terisi. Build/perangkat Android belum dijalankan/diperiksa; versi `omp` terpasang belum diverifikasi.
- Registry upstream adalah IPC lokal host untuk metadata/capability exact-generation, bukan endpoint discovery jarak jauh ([registry source](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/coding-agent/src/collab/registry.ts)). Jangan menambahkan akses registry ke aplikasi Direct Collab.

## Approach
### 0. Bekukan kontrak upstream sebelum implementasi
Baseline kontrak adalah checkout OMP commit `0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0` dengan package version `18.12.6`, `COLLAB_PROTO = 3`; ini bukan klaim bahwa tag/release npm `18.12.6` identik dengan commit tersebut. Package wire ada di `packages/wire`, bukan `packages/pi-wire`. Baca wire, crypto/link/relay, dan reducer web pada commit ini sebelum implementasi. Host menolak proto selain 3; jangan membuat fallback protokol lama.

Kontrak yang sudah dipastikan:
- Guest membuka `wss://<relay>/r/<roomId>?role=guest`; tidak ada Authorization, query token, HMAC/challenge, maupun room secret dalam URL koneksi.
- Enkripsi: `AES/GCM/NoPadding`, key 32 byte, IV acak baru 12 byte/frame, tag 128 bit, UTF-8 JSON, tanpa AAD/KDF/kompresi. Envelope: `[uint32 BE peerId][IV][ciphertext+tag]`; guest mengirim peerId 0.
- Hello terenkripsi: `{"t":"hello","proto":3,"name":"<nickname>","writeToken":"<base64url token>"}`; hilangkan `writeToken` untuk View. Token Control 16 byte, secret link Control total 48 byte; View total 32 byte.
- Welcome berisi `header,state,agents,entryCount,readOnly?`. Koneksi siap hanya setelah snapshot selesai; tidak ada resume cursor, sequence number, command ID, maupun ACK prompt/abort.
- `bye` terenkripsi adalah terminal. `room-closed` plaintext atau close 4001 bukan bukti sesi berakhir permanen: host dapat membuat ulang room yang sama. Close 4004 awal berarti room tidak ditemukan; 4004 setelah pernah tersambung adalah retryable.
- Protokol v3 mengharuskan menangani `ui-request`/`ui-request-end`. Tambahkan respons native minimum untuk `select`/`editor` sebagai interoperabilitas protokol, bukan sistem approval baru.

Referensi immutable: [wire](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/wire/src/index.ts), [crypto](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/coding-agent/src/collab/crypto.ts). E2EE protokol ini membuktikan penguasaan key, bukan identitas host; jangan menambahkan klaim host-authentication atau replay protection yang tidak dimiliki upstream.

### 1. Bootstrap aplikasi Android
Buat `:app` untuk Android/Compose/Hilt/platform storage dan `:collab` untuk protokol, OkHttp transport, serta replica pure Kotlin/JVM. Arah dependensi hanya `:app -> :collab`; tidak ada modul UseCase generik atau repository-per-DTO. Namespace/application ID `dev.omp.mobile`, label `OMP Mobile`, versionCode1/versionName`0.1.0`; minSdk26, compileSdk/targetSdk36; Java/Kotlin target17. Langkah2 parser/scanner dan langkah3 storage independen setelah bootstrap; langkah4–7 berurutan, lalu semua UI dirangkai pada langkah8. Backend metadata/protokol yang belum lengkap tidak diganti data contoh.

Pin di `gradle/libs.versions.toml` sebagai satu set; gunakan `google()`/`mavenCentral()` untuk dependency dan `gradlePluginPortal()` untuk plugin. Tidak ada versi dinamis.

| Komponen | Versi |
| --- | --- |
| Gradle wrapper / AGP | `8.13` / `8.11.1` |
| Kotlin Android/JVM/Compose/serialization/kapt plugin | `2.2.21` |
| Hilt plugin/runtime/compiler | `2.57.2`, kapt dengan `correctErrorTypes=true` |
| Compose BOM | `2025.10.01` (UI `1.9.4`, Material3 `1.4.0`) |
| Activity Compose / Lifecycle runtime-compose, viewmodel-compose, process | `1.11.0` / `2.9.4` |
| DataStore Preferences / CameraX camera2, lifecycle, view / ML Kit bundled QR | `1.1.7` / `1.5.1` / `17.3.0` |
| OkHttp / MockWebServer | `4.12.0` |
| Coroutines core, android, test / serialization-json | `1.10.2` / `1.9.0` |
| Mike Penz native Markdown core, m3, code | `0.37.0` |
| JUnit4 / AndroidX test runner / AndroidX test ext-junit | `4.13.2` / `1.6.2` / `1.2.1` |

Compose dependencies memakai BOM di atas. Jangan meng-upgrade Markdown ke `0.43.0` dalam baseline ini: POM tersebut memerlukan Kotlin2.4. Jangan menambah dependency Compose UI-test untuk skenario manual V4; instrumentation V3 menguji storage platform.

Versi dipilih sebagai baseline kompatibel, bukan klaim versi terbaru: [AGP compatibility](https://developer.android.com/build/releases/agp-8-11-0-release-notes), [KGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [Compose POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.01/compose-bom-2025.10.01.pom), [Markdown POM](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-m3-android/0.37.0/multiplatform-markdown-renderer-m3-android-0.37.0.pom). Belum ada build yang dijalankan.

Setelah approval, sediakan JDK17 user-local dan Android SDK di `$HOME/Android/Sdk` (atau instalasi nyata yang sudah tersedia, tanpa menimpa instalasi lain). Set JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT; dengan Android CLI resmi jalankan `android --no-metrics --sdk="$ANDROID_HOME" sdk install platform-tools platforms/android-36 build-tools/35.0.0`. Android Studio SDK Manager yang sudah terpasang boleh menyediakan paket identik; jangan menginstal skill/agent plugin tambahan. Buat wrapper dari distribusi Gradle8.13 resmi: `gradle wrapper --gradle-version 8.13 --distribution-type bin`, gunakan SHA256 dari `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256` sebagai distributionSha256Sum; jangan membuat wrapper JAR palsu. Tidak perlu NDK untuk aplikasi.

Gunakan MVVM + repository singleton yang mengekspos `StateFlow`, Hilt `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`; inject ViewModel ke UI dari Activity. `collectAsStateWithLifecycle()` untuk rendering; lifecycle socket bukan efek Composable.

### 2. Pairing QR/paste melalui parser yang sama
Semua path berikut baru; package prefix Kotlin `dev.omp.mobile`. Buat `collab/src/main/kotlin/dev/omp/mobile/collab/CollabLink.kt` dengan `fun parseCollabLink(raw: String): PairingParseResult`; hasil `Valid(PairingCredentials)` atau `Invalid(PairingError)` tidak boleh menyertakan raw input. `PairingCredentials` menyimpan origin, roomId, key32, token16 opsional dan men-redact string representation.

Port `protocol.ts::parseCollabLink` beserta `collab-web/test/link.test.ts`, bukan parser URL buatan yang hanya mengenali satu QR. Gunakan normalisasi URL OkHttp `HttpUrl` (map ws→http/wss→https saat parsing, lalu kembalikan scheme transport). Lakukan trim dan replace `%23` case-insensitive dahulu. Bare `<room>.<secret>` maupun legacy `<room>#<secret>` memakai default relay; scheme-less relay diberi `wss://`. Untuk wrapper http(s), fragment yang dapat diparse menang atas outer path; jika fragment invalid, validasi outer direct link. Gunakan encoded path/fragment agar percent-escape tidak diterima sebagai key mentah. Bersihkan userinfo/query/fragment ketika membangun endpoint koneksi.

Terima path `/r/<roomId>.<secret>` atau legacy `/r/<roomId>#<secret>`; roomId `[A-Za-z0-9_-]{10,64}`, key text `[A-Za-z0-9_-]+`, hasil decode hanya 32 atau 48 byte. Path secret menang atas fragment; trailing slash dan secret hilang/ukuran salah ditolak. `http(s)` direct dinormalisasi menjadi `ws(s)`. Tolak plain ws/http kecuali hostname localhost,127.0.0.1,::1; release hanya menghubungkan wss, sedangkan ws localhost hanya untuk debug smoke. Jangan membuat schema HMAC/token exchange baru.

Buat `app/src/main/kotlin/dev/omp/mobile/ui/pairing/PairingScreen.kt`: scan CameraX preview + ML Kit bundled QR-only dengan `InputImage.fromMediaImage(imageProxy.image, rotationDegrees)`, backpressure KEEP_ONLY_LATEST, tutup proxy pada semua success/failure/completion/null-image path. Kamera hanya aktif saat scanner terlihat; permission CAMERA diminta saat membuka scanner, bukan startup; camera feature `required=false`. Permission ditolak/tidak ada kamera = paste tetap berfungsi. Batasi ke satu hasil valid per pembukaan scanner, tampilkan error aman dan lanjut scan untuk QR invalid. Copy API dari [ML Kit Android guide](https://developers.google.com/ml-kit/vision/barcode-scanning/android).

Kontrak penyimpanan dan pemilihan UI dirangkai pada langkah7–8 setelah storage/client tersedia; pada langkah2 scanner hanya mengembalikan hasil parse ke callback, tanpa dependensi repository yang belum dibuat.

### 3. Penyimpanan pairing aman
Buat `app/src/main/kotlin/dev/omp/mobile/data/PairingStore.kt`. Gunakan satu singleton `PreferenceDataStoreFactory.create` dengan file `File(context.noBackupFilesDir, "omp.preferences_pb")`, scope IO, dan mutasi `edit` atomik. Jangan memakai DataStore default yang masuk backup, Room, plaintext SharedPreferences, atau EncryptedSharedPreferences.

Simpan list pairing sebagai satu blob ciphertext `vault_v1`. Payload JSON terenkripsi berisi `id` (UUID lokal), `label`, `relayOrigin`, `roomId`, `roomKey` (base64url 32 byte), `writeToken` (base64url 16 byte atau absent). Alias wrapping key `omp.mobile.pairings.v1`, AES256 dalam `AndroidKeyStore`, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, GCM/NoPadding, IV baru yang dihasilkan provider, tag128; AAD UTF-8 `omp-mobile:pairings:v1`. Nilai preference adalah Base64 tanpa newline dari `IV12 || ciphertext || tag`. Kunci room tidak dipakai sebagai wrapping key.

Preference nonsecret pada file yang sama: `nickname` default `Android`, `theme` bernilai `SYSTEM|LIGHT|DARK`, dan `last_pairing_id` UUID lokal. Capability dan raw scanned URL tidak pernah masuk SavedStateHandle, Bundle, route, exception, log, analytics, atau backup. Tipe credential harus men-redact `toString`; tidak memasang HTTP/frame logging interceptor. Hilangkan teks pairing dari layar setelah parsing/simpan; aplikasi tidak menyalin link ke clipboard otomatis.

Tambahkan manifest `allowBackup=false`; kredensial tetap di `noBackupFilesDir` karena aturan transfer OEM tidak identik dengan cloud backup. Keystore/JSON/IO di luar main thread. Empty file = belum ada pairing; I/O/keystore/tag/corruption = StorageError, bukan vault kosong atau plaintext. Key tidak ada tetapi vault nonkosong tidak boleh memicu key baru lalu menghapus ciphertext. Tampilkan pemulihan `Kredensial tidak dapat dibuka; hapus data aplikasi melalui Pengaturan Android lalu pair ulang`; jangan menambahkan reset file/key saat DataStore masih hidup. Sukses simpan hanya setelah write atomik berhasil. Reuse [factory API](https://developer.android.com/reference/kotlin/androidx/datastore/preferences/core/PreferenceDataStoreFactory), [Keystore](https://developer.android.com/privacy-and-security/keystore), dan [backup rules](https://developer.android.com/identity/data/autobackup).



### 4. Crypto dan transport guest tanpa memperluas protokol
Buat `collab/src/main/kotlin/dev/omp/mobile/collab/Wire.kt`, `CollabCodec.kt`, `OkHttpCollabTransport.kt`, dan `CollabClient.kt`. Tidak ada implementasi Kotlin lokal untuk digunakan ulang; transliterasikan kontrak upstream, gunakan JCA untuk crypto dan OkHttp untuk WebSocket, bukan implementasi crypto/WebSocket sendiri.

`Wire.kt` mendefinisikan tag wire sesuai `packages/wire/src/index.ts` dengan JSON `ignoreUnknownKeys=true`. Unknown entry tetap dihitung untuk progress snapshot tetapi tidak dirender; unknown frame/event diabaikan tanpa memutus koneksi. Malformed frame yang dikenal atau kegagalan GCM menjadi error protokol yang aman, bukan dianggap transcript kosong. Parser dan DTO tidak boleh mengubah wire names camelCase.

`CollabCodec` men-seal UTF-8 JSON satu kali per frame dengan key room yang diimpor satu kali per aktivasi; gunakan `SecureRandom` untuk IV baru. Guest envelope peer0 big-endian; peerId yang diterima bukan identitas tepercaya. Envelope <4 byte diabaikan sebagaimana upstream; payload terpotong/tag/key salah ditolak. Jangan menambah AAD pada wire (AAD vault pada langkah3 berbeda).

Transport membuka endpoint bebas secret dengan `OkHttpClient.newWebSocket`, memisahkan `onMessage(ByteString)` dari TEXT controls. Override `onClosing(webSocket,code,reason)` untuk mencatat kode remote, membalas `webSocket.close(1000, "")`, dan meneruskan satu close event ke actor; onClosed/onFailure sesudahnya tidak boleh menjadwalkan retry kedua pada epoch sama. Default listener no-op, jadi jangan menunggu onClosed saja ([source4.12.0](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/WebSocketListener.kt)). JSON session plaintext dari TEXT tidak diproses. Gunakan TLS/default trust manager dan hostname verification. Manifest INTERNET/ACCESS_NETWORK_STATE; cleartext default off; debug mengizinkan localhost/127.0.0.1/::1 saja.

Hanya `CollabClient` memiliki state machine dan satu coroutine actor yang men-serialisasi socket callbacks, decrypt, snapshot reducer, dan outbound control. Setiap aktivasi/koneksi mempunyai epoch monotonik; callback/timeouts dari socket lama tidak boleh mengubah sesi yang sudah berganti. Transport tidak memiliki retry loop sendiri. Penutupan disengaja memakai cancel/close yang ditandai lokal agar callback tidak memicu reconnect. Queue action tidak dibawa lintas socket.

Setiap onOpen memulai `welcomed=false`, mengirim hello terenkripsi dengan proto3 wajib terserialisasi, dan mengaktifkan timeout welcome30s. Sampai welcome valid, abaikan entry/event/state/agents/bus/ui-request agar broadcast sebelum handshake tidak mencemari replica; error dan bye tetap ditangani. Welcome.proto selain3 = Failed/ProtocolMismatch tanpa fallback. Welcome valid memulai progress timeout30s yang di-reset tiap chunk sampai snapshot lengkap. Terapkan timeout pada join awal dan reconnect. OkHttp pingInterval15s mendeteksi half-open; timeout pong lebih ketat dari silence40s upstream, bukan masalah wire. Jangan membuat watchdog frame aplikasi saja: host idle dapat sehat lewat pong.

Definisikan status `Idle`, `Connecting`, `Syncing(received,total)`, `Connected`, `Reconnecting(attempt,nextDelayMs)`, `WaitingForNetwork`, `Paused`, `Ended(reason)`, `Failed(kind,message)`. `Connected` berarti snapshot selesai, bukan sekadar HTTP101/onOpen. Reason/error yang ditampilkan dipetakan ke copy aman; jangan menyertakan capability, raw URL, payload atau exception transport mentah dalam log.

### 5. Replica journal, snapshot atomik, streaming, dan tool results
Buat `collab/src/main/kotlin/dev/omp/mobile/collab/SessionReplica.kt`. Semantik transcript mengikuti web `/collab`: semua journal entries dalam urutan append, dengan marker branch/compaction; jangan merekonstruksi active branch atau konteks TUI. `parentId` disimpan, tidak dipakai untuk memfilter journal. Gunakan referensi `GuestClient.#applyFrame/#applyEvent` dan `components/transcript/Transcript.tsx`; tidak ada reducer Kotlin yang sudah tersedia.

- Setiap welcome memulai staging baru: header/state/agents/readOnly, entryCount, entries, dan buffer live entry. `entryCount=0` langsung mempromosikan snapshot kosong. Selain itu, akumulasi chunk hingga `final=true` ATAU jumlah entry yang diterima mencapai entryCount; orphan chunk diabaikan.
- Selama staging, transcript lengkap lama dari pairing yang sama tetap terlihat dengan label Syncing; entry live masuk buffer, event/state/agents diterapkan ke staging, bukan mencampur ledger lama. Setelah lengkap, ganti snapshot atomik dan terapkan live buffer. Disconnect membuang staging yang belum lengkap. Memilih pairing berbeda mengosongkan layar lama agar data dua sesi tidak tercampur.
- Indeks durable entries berdasarkan `entry.id`, pertahankan urutan pertama dan upsert ID berulang agar resync/replay tidak menggandakan baris. Progress memakai jumlah entri wire yang diterima, termasuk varian yang tidak dirender. Snapshot baru mengganti ledger, bukan append ke ledger lama.
- `message_start/message_update.message` adalah FULL accumulating message: replace ghost assistant, jangan concatenate delta. `message_end` menandai ghost selesai tetapi jangan menghapus sebelum durable assistant entry tiba. Entry assistant dengan `(timestamp,model)` yang cocok menghapus ghost walaupun event message_end terlewat; entry lama dengan identitas berbeda tidak menghapus stream aktif. Identitas ini hanya korelasi UI, bukan ACK/security identity.
- Tool aktif diindeks `toolCallId`; start membuat card, update mengganti `partialResult`, end mengakhiri status running. Final result berasal dari durable `toolResult` yang dipasangkan secara global ke `toolCall.id`, termasuk jika call dan result berjauhan di luar viewport. Jangan membuat bubble kedua untuk toolResult.
- `state.isStreaming` adalah otoritas busy; idle membersihkan active tool yang kehilangan end event dan overlay selesai. Render user/assistant; `custom_message` tipe `collab-prompt` menjadi user row dengan `details.from`, custom lain hanya jika display=true. Compaction/branch_summary/model_change/thinking_level_change menjadi marker. Developer/unknown rows dilewati seperti web.
- Simpan list/index entries sampai benar-benar berubah; jangan menyalin atau memindai seluruh riwayat pada tiap token/state/agent update. Proses semua frame berurutan, tetapi publish message_update/tool_execution_update maksimal sekali per16ms; frame terminal/entry/state flush segera. Key Compose memakai entry ID/toolCallId dan satu key ghost stabil.

### 6. Prompt, interrupt, dan interaksi wajib proto3
Tambahkan API pada `CollabClient`: `suspend fun sendPrompt(text: String): SendResult`, `suspend fun interrupt(): SendResult`, `suspend fun respond(reqId: Long, value: String?): SendResult`. `SendResult` memiliki `Queued`, `NotConnected`, `ReadOnly`, `Rejected`; Queued hanya berarti diterima queue socket lokal, bukan diterima/dijalankan host. Semua aksi melalui actor yang sama dengan state machine.

- Mutasi hanya ketika Connected dan link membawa token serta welcome.readOnly tidak true. Prompt kosong/whitespace tidak dikirim. Pertahankan teks nonkosong persis yang diketik. Prompt wire `{"t":"prompt","text":"..."}`; interrupt `{"t":"abort"}`. Ketika host busy, prompt tetap boleh dikirim sebagai steering sesuai upstream. Disable interrupt setelah tap sampai host state/event atau disconnect merekonsiliasi.
- Tidak ada command ACK/idempotency key dalam protokol: jangan local-echo entry, menambahkan field requestId pada prompt, atau mengirim ulang otomatis setelah reconnect. `send=false` mempertahankan draft; `send=true` mengosongkan composer, simpan last-submission hanya di memory. Bila koneksi hilang saat ada pengiriman, tampilkan peringatan bahwa perintah mungkin sudah dijalankan dan pengguna harus memeriksa transcript setelah resync sebelum mengirim ulang. Aplikasi tidak boleh mengklaim exactly-once.
- Error host sebelum welcome menutup percobaan dan menjadi Failed/JoinRejected; sesudah welcome menjadi notice tanpa menghapus transcript atau mengirim ulang prompt. Missing/invalid write token dapat menghasilkan welcome.readOnly=true: downgrade UI walaupun link tampak Control.
- Antrekan ui-request berdasarkan reqId, satu dialog aktif. Select menampilkan string option atau object.label/description, initialIndex, helpText, checkedIndices dan markableCount. Tap satu row mengirim label persis yang diberikan host, bukan indeks/array. Checkbox multi-select adalah beberapa round request/response dengan reqId baru; host menyediakan sendiri row `Next →`, `Done selecting`, atau label aksi lain, jangan hardcode sentinel.
- Editor menampilkan title/prefill, submit mengirim isi string termasuk whitespace/empty jika memang user submit; cancel eksplisit/Back mengirim `{"t":"ui-response","reqId":N}` tanpa value. Konfigurasikan encoder agar nullable default null untuk response.value dihilangkan (`explicitNulls=false`/`encodeDefaults=false`), tetapi hello.proto tetap required/non-default. Jangan mengirim value=null atau string sentinel untuk cancel.
- Setelah jawaban ter-queue, disable double submit untuk reqId itu dan tunggu ui-request-end. Frame end men-dismiss tanpa respons dan memajukan antrean; end untuk request yang belum aktif menghapusnya dari antrean. Disconnect, welcome baru, background, dan ganti sesi membersihkan dialog tanpa cancel/response. Pending request yang masih valid direplay host setelah snapshot; jangan memulihkan jawaban lama atau mengirim response sebelum hello. Read-only defensif mengabaikan ui-request.

### 7. Satu pemilik koneksi: repository, lifecycle, dan reconnect
Buat `app/src/main/kotlin/dev/omp/mobile/data/CollabRepository.kt` (interface dan implementasi `DefaultCollabRepository`) dan `app/src/main/kotlin/dev/omp/mobile/data/AndroidSessionLifecycle.kt`, wire Hilt singleton ke `CollabClient`. Kontrak repository:

```kotlin
interface CollabRepository {
    val pairings: StateFlow<List<SavedPairing>>
    val session: StateFlow<CollabSessionState>
    suspend fun pair(rawLink: String, label: String?): PairingResult
    suspend fun select(id: String)
    suspend fun rename(id: String, label: String)
    suspend fun forget(id: String)
    fun retry()
    fun disconnect()
    suspend fun sendPrompt(text: String): SendResult
    suspend fun interrupt(): SendResult
    suspend fun respond(reqId: Long, value: String?): SendResult
}
```

SavedPairing hanya UUID, label, relay host, roomId, dan metadata akses; UI tidak menerima key/token. PairingResult = Saved(id), Invalid(reason), atau StorageError; Saved bukan berarti sudah Connected. ID selection yang hilang saat restore dibersihkan lalu tampilkan Sessions; select manual ID yang hilang menghasilkan notice tanpa mengganti target; forget ID hilang idempotent. Label kosong saat pairing baru menjadi `Sesi <relayHost> / <6 karakter pertama roomId>`; rename kosong ditolak dengan error inline, bukan menghapus label.

- Pairing baru disimpan lalu dipilih. `(relayOrigin,roomId)` sama mengganti credential secara utuh dan mempertahankan UUID; label lama dipertahankan jika label input kosong, selain itu pakai label baru. Scan View setelah Control menurunkan akses, tidak mempertahankan token lama. Room berbeda disimpan sebagai entri baru. Forget aktif menutup socket dan menghapus selected ID, bukan stop/abort host. Tidak ada discovery/generation/gateway atau penggantian room tersembunyi.
- Simpan boolean in-memory `autoConnectWanted`; true saat select/pair/retry, false saat disconnect manual atau terminal Failed/Ended. Persist last_pairing_id hanya untuk target yang masih diinginkan; manual disconnect/terminal menghapus ID auto-restore tetapi tetap menyimpan pairing dan transcript in-memory. ProcessLifecycleOwner ON_START mengaktifkan target hanya jika autoConnectWanted; ON_STOP membatalkan retry/timers/socket aktif dan beralih Paused tanpa mengubah keinginan reconnect. Failed/Ended/Idle tidak ditimpa Paused dan tidak dibuka lagi oleh lifecycle/network event. Process death membaca last_pairing_id lalu memulihkan tanpa scan jika sebelumnya tidak dihentikan/terminal. Tab navigation dan rotasi tidak membuat koneksi baru; transcript hanya memory, tanpa Room/offline cache. Reuse [ProcessLifecycleOwner](https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner), bukan Activity.onPause.
- Register satu default NetworkCallback saat foreground, unregister saat pause ([network guide](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)). onAvailable mencatat default network baru; tunggu onCapabilitiesChanged pertama sebelum satu percobaan koneksi. Bandingkan network identity/transport agar callback identik tidak churn; onLost network lama diabaikan jika default sudah berganti. Network hilang = WaitingForNetwork tanpa retry timer; network baru/transport berubah membatalkan socket lama dan memicu satu percobaan bila autoConnectWanted. VALIDATED false→true hanya mempercepat retry yang sedang menunggu, tidak merusak koneksi Connected sehat. VALIDATED bukan syarat mutlak custom/private relay. Jangan bindProcessToNetwork, memaksa Wi-Fi atau bypass VPN.
- Backoff `min(1000 * 2^attempt, 30000) * (0.75 + random*0.5)` ms; gunakan perhitungan saturating agar attempt besar tidak overflow. Maksimal satu socket dan satu retry timer. Reset attempt hanya setelah welcome+snapshot sukses, bukan setiap onOpen, agar siklus open/close tidak hot-loop. Welcome/progress timeout adalah transport retry dengan backoff; jelaskan `Host belum merespons`, jangan menyebut key salah tanpa bukti GCM.
- Mulai setiap aktivasi fresh dengan `everWelcomed=false, roomRecovery=false`; valid welcome men-set everWelcomed. Close4001 men-set roomRecovery lalu retry;4004 retry jika everWelcomed atau roomRecovery pada aktivasi itu. Initial4004 tanpa keduanya = Failed/RoomNotFound (`Room tidak ditemukan; pair ulang atau coba lagi setelah host siap`). Pause/disconnect/seleksi baru mereset aktivasi; karena itu room yang hilang selama background tidak dicoba tanpa batas saat resume.
- Encrypted bye = Ended(reason), tidak autoretry. Close4009/4029, kegagalan GCM/protokol, dan join rejection sebelum welcome = Failed, tidak autoretry. TEXT room-closed hanya hint teardown; tidak membuktikan sesi berakhir.1000/1006/I/O tak disengaja dan4001 = retry. Manual Retry memulai aktivasi baru, bukan melanjutkan queue aksi lama.
- Jika host mati tanpa bye setelah aplikasi sudah live, tidak ada sinyal untuk membedakannya dari gangguan sementara: terus retry dengan cap backoff selama foreground, tampilkan `Menyambungkan kembali`, sediakan `Berhenti menyambung` dan Pair ulang. Label ini berbeda dari Interrupt agent. Jangan memakai timeout untuk mengklaim host berakhir atau mencoba menemukan room baru. Room baru hanya dari scan/paste baru.

### 8. Lengkapi tiga layar native di atas kontrak yang sudah bekerja
Buat `app/src/main/kotlin/dev/omp/mobile/ui/{sessions/SessionsScreen.kt,chat/ChatScreen.kt,settings/SettingsScreen.kt}` beserta ViewModel per layar dan `MainActivity.kt`/`OmpMobileApplication.kt`. Gunakan satu Material3 Scaffold, bottom NavigationBar Sessions/Chat/Settings, tab state enum yang saveable tanpa secret, dan scanner sebagai route/overlay terpisah; tidak perlu library navigasi tambahan untuk tiga tab.

- Sessions: empty state dengan Scan QR/Paste link, list label+relay host+View/Control, tombol pilih/ganti label/hapus lokal. Pemilihan otomatis membuka Chat. Pairing confirmation langkah2 menampilkan metadata aman dan `Simpan & hubungkan`; error simpan tidak melaporkan sukses. Daftar adalah pairing tersimpan, bukan klaim host aktif.
- Chat: header session title dari header.title/state.sessionName, cwd/basename proyek, status koneksi/permission, model.name atau model.id, contextUsage.percent jika tersedia, queue count, dan ringkasan status subagents dari agents kind=sub. Jangan mengisi git branch, context%, model, atau jumlah agen dengan data contoh ketika host tidak memberikannya. Ringkasan subagent tidak membuka remote transcript viewer atau agent-cmd.
- LazyColumn journal dengan stable keys, selectable native Markdown (`com.mikepenz.markdown.m3.Markdown`, async rememberMarkdownState), codeBlock/codeFence dari modul code/highlightedCodeBlock/highlightedCodeFence; copy API [README v0.37.0](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/v0.37.0/README.md), bukan API streaming/retainState versi lebih baru. Thinking collapsed default, redactedThinking hanya label redacted, tool cards expandable args/output dan badge running/success/error. Inline image wire ditampilkan thumbnail dengan decode background dan bounds/sample size; tidak membuat upload. Native rendering, bukan WebView; jangan auto-fetch remote image atau menjalankan HTML/javascript. Buka link http(s) hanya setelah tap pengguna.
- Follow tail hanya jika pengguna sedang di bawah; scroll membaca riwayat tidak ditarik turun tiap token. Sediakan kembali-ke-terbaru. Composer multiline ber-IME padding, Send, dan Stop/Interrupt sesuai status; View/Syncing/Paused/Failed tidak memiliki aksi mutasi. Draft berbeda per pairing, hanya memory; status lama saat reconnect diberi label stale, bukan Connected.
- Dialog select/editor langkah6 dan connection error/retry berada di atas Chat tanpa kehilangan transcript. Tidak membikin form persetujuan terpisah dari request host. Status selalu memakai teks, bukan warna saja; icon buttons punya contentDescription, touch targets48dp, TalkBack tidak mengumumkan tiap token.
- Settings: nickname (trim≤64 chars, empty kembali Android, berlaku saat join berikutnya), theme System/Light/Dark, versi aplikasi/wire3 dan baseline sumber yang diuji, penjelasan foreground-only. Tidak ada tombol background service, gateway, cache offline, biometric, ataupun setting palsu.


## Critical files & anchors
Semua anchor ini berada di repo `can1357/oh-my-pi` pada commit `0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0`; nomor baris hanya petunjuk, reread sebelum implementasi.

1. [`packages/wire/src/index.ts`](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/wire/src/index.ts), `GuestFrame`, `HostFrame`, `AgentEvent`, `SessionState`, `COLLAB_PROTO` — skeleton JSON dan required/optional/null.
2. [`packages/collab-web/src/lib/client.ts`](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/collab-web/src/lib/client.ts), `#applyFrame`, `#applyEvent`, `#publishedEntries` — staging/resync, full-message replacement, tool overlays. Jangan menyalin kelemahan timeout welcome initial-only atau outbound replay.
3. [`packages/collab-web/src/lib/socket.ts`](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/collab-web/src/lib/socket.ts), `#handleClose`, `#scheduleRetry` — close codes/jitter; jangan menyalin queue replay sebelum hello.
4. [`packages/collab-web/src/components/transcript/Transcript.tsx`](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/collab-web/src/components/transcript/Transcript.tsx), `EntryRow`, `AssistantBody` — journal dan marker yang dipilih; tool-result lookup Android harus global, bukan hanya window100.
5. [`packages/coding-agent/src/modes/controllers/extension-ui-controller.ts`](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/coding-agent/src/modes/controllers/extension-ui-controller.ts), select loop sekitar1002–1051 dan retainOnReconnect — multi-select adalah round string, bukan array.

## Verification
Semua perintah di bagian ini dijalankan setelah approval; belum ada klaim build, test, atau perangkat lolos saat perencanaan. Root kerja Android: `/home/wush/playground/omp-mobile`. Prasyarat: JDK17 dan SDK langkah1, satu emulator/device terdaftar `adb devices -l`, serta host OMP nyata wire3 dengan provider/model yang sudah terautentikasi. Gunakan fixture publik, bukan credential pengguna, untuk test deterministik.

### V1. Conformance parser dan AES/envelope — langkah2,4
Buat test JVM `collab/src/test/kotlin/dev/omp/mobile/collab/CollabLinkTest.kt` dan `CollabCodecTest.kt`; JUnit4. Salin behavior cases [link.test.ts](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/collab-web/test/link.test.ts) dan [codec.test.ts](https://github.com/can1357/oh-my-pi/blob/0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0/packages/collab-web/test/codec.test.ts), bukan assertion terhadap source text.

- Input Control publik: `https://my.omp.sh/#AbCdEf123456_-Xy.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh_w8fLz9PX29_j5-vv8_f7_` → endpoint `wss://my.omp.sh/r/AbCdEf123456_-Xy?role=guest`, room key bytes00..1f, token f0..ff, encrypted hello.writeToken `8PHy8_T19vf4-fr7_P3-_w`. HTTP/WebSocket URL, error dan string representation tidak mengandung secret.
- View secret `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` →32byte/no token. Uji wrapper-fragment precedence, compact custom relay, dot/legacy/#/%23, IPv6 local/default ports, invalid16/40byte secret, invalid alphabet, missing key, trailing slash dan nonlocal cleartext rejection.
- Golden key `AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK-2vcTL0tk`, sealed `m0PA1QNfpOGtl_iq1yfKhoux0moFN_WQtCExumBVOWKeHFY_yx7T4s3B5YFUSn6Dc9aAyVsjIjPQXLxqsg8_UQiZ9Q` → plaintext bytes persis `{"t":"hello","proto":1,"name":"vector"}`. Dengan IV12 dari golden, enkripsi plaintext yang sama harus menghasilkan sealed golden byte-for-byte; fixed IV hanya test. Proto1 di sini hanya codec vector, bukan versi handshake yang diterima client.
- Bit flip tag, wrong key, truncated sealed → auth/decode failure, tanpa cleartext fallback; peer prefix `01 02 03 04` terbaca0x01020304, guest outbound prefix00000000.

### V2. Replica dan state-machine transitions — langkah4–7
Buat `SessionReplicaTest.kt`, `CollabClientTest.kt`, `CollabInteractionTest.kt` pada package test yang sama. Gunakan MockWebServer actual WebSocket upgrade + sealed frames dan coroutine virtual time; test fixture tidak masuk APK. Hindari sleep/live internet dalam test permanen.

- Ledger lama[A], welcome entryCount2, chunk[B] nonfinal, live entry[D], chunk[C] final → selama load tetap[A], setelah load tepat[B,C,D]; replay ID D tidak menambah baris. Empty snapshot menghapus ledger. Drop tengah snapshot lalu welcome baru tidak membawa partial lama.
- Update partial `"hel"` lalu `"hello"` → ghost `"hello"`, bukan `"helhello"`. Durable assistant entry menghapus ghost termasuk bila message_end terlewat; entry lama tidak menutup stream baru. Tool call/result yang dipisah>100entry tetap satu card/result benar; state idle menghapus running overlay yang kehilangan end.
- Full Control link + welcome.readOnly=true → tidak ada mutating frame untuk prompt/abort/response. Syncing/Paused/Failed juga menolak mutasi. Unknown entry tetap menyelesaikan count; known malformed frame tidak dipromosikan sebagai transcript kosong.
- Send prompt sekali lalu putuskan socket sebelum respons: reconnect dimulai hello, host tidak menerima pengiriman ulang prompt/abort/UI answer. Status hanya Queued/uncertain, bukan delivered. Rapid double-tap UI response untuk reqId sama menghasilkan satu pengiriman.
- 4001→4004→welcome/chunks dan1006 setelah welcome→4004→welcome/chunks pulih; initial4004 terminal. Remote onClosing4004 yang diikuti onClosed/onFailure menghasilkan satu keputusan, bukan retry ganda.4009/4029, bad GCM, welcome.proto2 dan pre-welcome error terminal. Bye/Failed/disconnect manual tidak autoreconnect akibat lifecycle/network atau reopening proses. TEXT room-closed saja bukan Ended. Broadcast entry/event sebelum welcome diabaikan; snapshot setelahnya tetap lengkap tanpa duplikasi.
- Network hilang membatalkan timer; available berulang tidak membuat socket ganda; ganti sesi ketika callback lama masih berjalan tidak mencemari sesi baru. Welcome/progress stall30s saat reconnect tetap menghasilkan retry/backoff; cap tidak overflow. Background menutup koneksi tanpa host abort/UI cancel.
- Select checkbox round req1 `Option A`, req2 dengan checkedIndices[0] dan label host `Next →` → satu string per round, bukan array. End request lama saat dialog lain aktif tidak menghapus dialog baru. Resync/dismiss tanpa user-cancel tidak mengirim response; editor submit whitespace berbeda dari cancel omitted-value.

### V3. Keystore dan proses Android — langkah3,7
Buat `app/src/androidTest/kotlin/dev/omp/mobile/data/PairingStoreInstrumentedTest.kt` dengan file/alias unik per test agar tidak menyentuh vault nyata. Buktikan credential fixture dapat dibaca ulang melalui Keystore, file tidak mengandung raw/base64 key-token/link, tamper ciphertext atau key tidak tersedia menghasilkan StorageError (bukan empty/default/plaintext fallback), dan upsert View tidak mempertahankan token Control. Gunakan singleton DataStore per file; tutup scope test sebelum membuka instance baru untuk file sama.

Sesudah integrasi, jalankan sekali dari root Android:
```sh
java -version
adb devices -l
./gradlew :collab:test :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.omp.mobile/.MainActivity
```
Jika beberapa device aktif, pilih satu melalui ANDROID_SERIAL untuk rangkaian ini. Artefak installable adalah `app/build/outputs/apk/debug/app-debug.apk`; signing/distribusi Play bukan syarat build ini.

### V4. Smoke end-to-end host nyata + layar native — langkah2–8
Siapkan checkout upstream baru di direktori sementara, checkout commit baseline, lalu dari root checkout jalankan `bun install --frozen-lockfile` dan `bun run build:native` bila addon lokal belum tersedia (Cargo/Rust dan toolchain C/C++ adalah prasyarat host, bukan dependency APK). Mulai host dengan `bun run dev`; jangan menjalankan `bun run setup` karena script tersebut juga mengubah global link omp. Pada host dengan model aktif:
```text
/collab
/collab view
```

1. Scan QR Control dari host → Chat Connected hanya setelah snapshot; Scan/Paste invalid memberi error tanpa bocor secret. Tolak camera permission lalu paste link yang sama → tetap bisa join. Tidak memasukkan capability ke argumen adb/log/screenshot QR.
2. Kirim prompt unik yang meminta `pwd` read-only dan contoh fenced JSON. Host menampilkan satu collab prompt dari nickname Android; native menerima streaming, Markdown/code highlight dan tool card/result. Saat sedang tool/stream lain (misalnya permintaan read-only `sleep 30`), tap Interrupt → host berhenti bekerja dan native keluar dari busy tanpa bubble final ganda. Jangan mengubah file melalui prompt smoke.
3. Join link View secara terpisah → transcript tersinkron, label View, composer/Interrupt/dialog-response tidak bisa memutasi host. Ganti antara dua pairing manual → hanya satu guest aktif untuk aplikasi, transcript tidak tercampur.
4. Gunakan ask/select/editor nyata dari host untuk menjawab satu opsi, satu checkbox multi-round, editor, dan cancel. Dialog berakhir pada semua writable peer. Background saat ask masih pending, lalu resume → replay request yang masih valid tanpa mengirim cancel otomatis.
5. Buka Settings, ganti theme/nickname, kembali Chat, rotasi dan buka keyboard → tidak ada socket ganda, transcript tetap terbaca, composer tidak tertutup IME. Scroll ke riwayat saat token datang → tidak meloncat ke bawah; kembali-ke-terbaru bekerja. Periksa TalkBack label tombol dan status nonwarna. Rekam screenshot Chat/Sessions/Settings tanpa credential.

`collab-web/scripts/mock-host.ts` boleh membantu smoke visual fixture, tetapi linknya bare32byte dan tidak memverifikasi write token seperti host nyata: bukan bukti kontrol/otorisasi. Smoke host nyata tetap wajib.

### V5. Recovery nyata — langkah5,7
Gunakan host/room yang sama selama skenario; jangan menyelesaikan reconnect dengan membuat /collab baru.

- Pada ponsel fisik dengan Wi-Fi dan data seluler aktif, biarkan Chat foreground; `adb shell svc wifi disable` lalu `adb shell svc wifi enable` memicu Wi-Fi→mobile→Wi-Fi. Tunggu Connected, tanpa scan ulang, transcript host lengkap tepat sekali, tidak ada prompt yang direplay. Jika shell tidak boleh mengatur radio, lakukan pergantian lewat OS; catat jika skenario itu juga menyebabkan background, bukan mengklaim pure foreground handoff.
- Hilangkan semua jaringan, lalu pulihkan: WaitingForNetwork tanpa retry hot-loop, satu percobaan segera setelah network kembali. VPN on/off bila tersedia harus berujung resync yang sama, tanpa memaksa bypass VPN. Kembalikan setting radio perangkat ke kondisi semula.
- `adb shell input keyevent KEYCODE_HOME`; buat entry baru melalui terminal host saat aplikasi background. `adb shell am start -n dev.omp.mobile/.MainActivity` → memakai pairing tersimpan dan entry baru muncul tepat sekali. Ulangi dengan `adb shell am force-stop dev.omp.mobile`, lalu buka kembali → Keystore/DataStore tetap bisa memulihkan pairing tanpa scan.
- Untuk host/relay teardown race tanpa mematikan OMP, dari checkout jalankan `bun run collab:relay` (localhost7466), gunakan `/collab ws://localhost:7466`, dan `adb reverse tcp:7466 tcp:7466`. App debug memakai localhost, bukan10.0.2.2 yang ditolak parser plain-ws. Hentikan/restart proses relay lokal pada port sama; host tetap hidup → guest retry termasuk race4004, memperoleh full snapshot dan tidak menduplikasi ledger. Jangan memakai firewall/global network changes pada workstation untuk mensimulasikannya.
- Saat guest live, host `/collab stop` → encrypted bye menghasilkan Ended dan retry berhenti. Stop host saat guest background → resume fresh mendapat RoomNotFound, bukan janji recovery. Buat room baru hanya untuk membuktikan link lama tidak menemukan room pengganti; scan/paste link baru diperlukan dan pairing lama tidak diam-diam ditimpa.

### V6. Bukti kompatibilitas versi
Laporkan terpisah: commit sumber fixture, COLLAB_PROTO3, versi/commit host yang benar-benar dijalankan, device/API dan relay lokal/publik yang dipakai. Tag `v18.12.6` juga mendefinisikan proto3/key32/token16, tetapi package version sama tidak membuktikan commit sama. Jalankan happy-path V4 minimal terhadap satu host nyata wire3 di relay publik wss dan recovery V5 pada relay lokal; catat versi aktual lewat `omp --version` untuk host terpasang atau commit checkout untuk `bun run dev`. Negative-proto V2 membuktikan tidak ada downgrade diam-diam.

Selesai berarti test perilaku, installable APK, UI nyata, prompt/interrupt/ask, persisted resume, serta recovery yang disebut di atas memiliki evidence. Jangan mengganti bukti platform/live dengan build hijau, canned transcript, atau mock echoes.

## Assumptions & contingencies
- Default produk yang dapat diubah pengguna: Android8/API26 ke atas, package `dev.omp.mobile`, beberapa pairing manual dengan satu koneksi aktif, dan antarmuka berbahasa Indonesia. Tidak ada publish Play/keystore signing produksi yang diminta.
- Jika provider/model belum terautentikasi atau tidak ada host nyata, selesaikan seluruh implementasi dan test lokal yang terjangkau tetapi nyatakan live E2EE/control smoke terblokir pada prerequisite tersebut; jangan mengirim credential ke app atau mengganti host dengan backend/fallback palsu. Bila build native upstream tidak tersedia, host OMP terpasang yang terbukti wire3 boleh menjadi target live; laporkan versi yang diuji tanpa mengklaim checkout baseline telah dijalankan.
- Jika adb/perangkat belum tersedia, jangan mengklaim QR/Keystore/UI atau Wi-Fi↔mobile telah diverifikasi. Sediakan APK dan hasil JVM/build; runtime Android memakai emulator API26/API36 ketika tersedia, sementara handoff Wi-Fi↔mobile memerlukan ponsel fisik. Jangan menganggap emulator-only network toggle membuktikan handoff seluler nyata.


## Implemented extensions and current delivery

This section supersedes the original UI exclusions above. The pinned wire contract remains
`0b5c27ab24719c86a5986b95ee55f4e75bc1aaa0`, protocol 3; no host-side protocol extension is shipped.
The current build uses the versions in `gradle/libs.versions.toml` (AGP 9.4.1, Gradle 9.8.2,
Compose BOM 2026.09.00, Material 3 1.5.0-beta02, compile/target SDK 37, min SDK 26).
User-facing app text is English.

### Gallery, icons, and code

- Chat's image button opens the Android photo picker for up to four images. Preview, remove,
  image-only prompts, and text-plus-image prompts use the existing encrypted `prompt.images`
  wire field; this does not introduce an upload backend.
- Image preparation runs on IO with sampled stream decoding, EXIF orientation, JPEG compression
  and small previews. Maximum dimension 1600px; encoded JPEG at most 2,000,000 bytes per image;
  combined attachment base64 at most 8,000,000 characters. Drafts remain in memory per pairing.
  A picker-induced foreground pause does not discard the selected images. Rejected sends keep
  the draft; completion from an earlier pairing cannot clear another pairing's draft.
- The host/provider must accept vision input. View links cannot send images. An uncertain,
  already-queued prompt is never automatically resent.
- Navigation and controls use official Material Symbols Rounded XML assets. Source and copyright
  are recorded in each drawable; the redistribution license accompanies the APK in
  `dist/material-symbols-LICENSE.txt`. Android's [icon guidance](https://developer.android.com/develop/ui/compose/graphics/images/material)
  recommends these assets rather than the legacy `material-icons` dependency, which was removed.
- Native fenced code has language labels, background syntax highlighting, horizontal scrolling,
  monospace text, and an explicit copy action. Indented/unknown-language code remains readable
  without guessed highlighting. Colors follow the app's chosen theme, not just the system theme.
  The renderer stays pinned to [v0.37.0](https://github.com/mikepenz/multiplatform-markdown-renderer/tree/v0.37.0).

### Todo and subagents

- Todo checklists render actual `todo` result `details.phases[].tasks[]` from the pinned host:
  pending, in-progress, completed, abandoned and blocked, including blocker text. Only the latest
  successful canonical update is expanded as the current checklist; failed updates remain errors.
  Missing host details are not replaced with a fabricated checklist.
- The subagent panel exposes the host's list, status and available progress/lifecycle traffic.
  Control links can chat, kill and revive; View links can read a transcript when the host reports
  `hasSessionFile`. Transcript reads use correlated requests, a 10-second timeout, committed byte
  offsets, manual incremental loading and selectable raw JSONL. Disconnects and pairing changes
  invalidate pending reads. Agent commands are not replayed or claimed acknowledged.
- Plan/Vibe switching and model selection are not supported by protocol 3 guest frames. No
  decorative toggle or unreliable slash-prompt substitute is included; these need host support.

### Automatic connection recovery

- Socket enqueue refusal or a transport-send exception while Connected now closes the stale
  attempt and enters automatic backoff/reconnect/resync. Previously this branch returned Rejected
  while leaving the UI Connected until another close callback arrived.
- Idle connections retain OkHttp's 15-second ping/pong watchdog. Transport failures, retryable
  closes and network changes recover through the single client owner. Transcript history remains
  visible until the replacement snapshot is complete.
- This remains a foreground-only connection: background pauses it, returning resumes it.
  Manual disconnect, encrypted bye, invalid keys/protocol and terminal join failures are not
  overridden by a reconnect loop. Socket recovery never replays prompts or agent commands.

### Verification of this extension

All Gradle builds and Android runtime checks ran on the tbs-dev cluster, not local Docker/Gradle.

| Check | Observed result |
| --- | --- |
| `:collab:testDebugUnitTest` | 73 tests, 0 failures/errors |
| `:app:assembleDebug`, `:app:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| Installed Android instrumentation, emulator API 36 | 10 tests passed: Keystore, image preparation, draft isolation and failed-send retention |
| Native QR gallery pairing | Public synthetic Control link connected after snapshot |
| Native gallery selection | Two previews, removal to one, image-only send received as one JPEG |
| Native subagent actions | Chat, kill, revive; transcript reads from byte 0 then 382 |
| Native code blocks | Exact clipboard text/indentation preserved; app Light/Dark overrides exercised |
| Idle unresponsive socket | Ping failure followed by automatic rejoin in 28.71 seconds |
| Drop immediately after a prompt | Automatic rejoin; exactly one matching prompt received, no replay |

The interaction host was a throwaway AES-GCM wire-3 fixture with public test credentials, not an
OMP process or authenticated model/provider. These checks do not substitute for the original
real-host V4/V5 matrix, physical Wi-Fi/cellular handoff, background execution, or an API-26 device run.
The debug APK is `dist/omp-mobile-0.1.0-debug.apk`; its adjacent SHA256 file is the delivery checksum.

### QR session-frame compatibility and safe diagnostics

- Host history can include internal message roles (`bashExecution`, `pythonExecution`) and
  provider replay blocks outside the rendered wire subset. Unknown roles still count toward the
  snapshot but are not rendered; unknown block types are skipped without dropping known text or
  tool calls. Missing required fields remain terminal protocol errors, not retry loops.
- The debug APK logs structured `OmpCollab` records for connection attempts, socket open/hello,
  welcome/snapshot/ready, transport failures, close codes, retry delays and parser/decryption
  failures. Records contain only enums and numeric metadata: local activation/socket epochs,
  encrypted wire-frame byte sizes, entry counts, protocol version and validation codes.
  Pairing links, room IDs, keys/tokens, nicknames, paths, chat/tool/image content, remote close
  reasons and raw exception messages/causes are not logged. Streaming updates produce no logs.
  Release builds do not install the diagnostic callback.
- Parser errors also display the safe frame/validation code and wire size in the connection
  banner, for example `SNAPSHOT_CHUNK/CONTENT_TYPE; 195 bytes`.
- To collect debug connection logs, run `adb logcat -s OmpCollab:D '*:S'`, reproduce the issue,
  and report the safe error code and filtered log rather than a pairing QR/link.
- Cluster verification: **79 JVM tests, zero failures/errors; 10 Android tests passed on API 36**.
  The previously shipped APK reproduced `Failed: Invalid session frame` after native QR-gallery
  pairing. The repaired APK connected, rendered the retained assistant text, accepted live
  updates, automatically rejoined after a socket close, and reported a deliberately malformed
  frame with safe diagnostics. Credential/payload exclusion was checked against captured logs.
- This repair used the published `@oh-my-pi/pi-coding-agent@18.12.6` production `CollabHost` and
  `SessionManager` with an in-process test relay and test TUI context, **not an authenticated
  model/provider run**. Evidence is under `evidence/qr-parser-fix/`; the original physical
  Wi-Fi/cellular and real-model acceptance limits remain unchanged.
- This build uses a fresh cluster debug signing key. Android rejected an in-place update over
  the previous debug APK with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; clean installation passed.
  If updating is rejected, uninstall the old debug app and pair again. Uninstalling removes
  its locally saved pairings/settings. No production signing key was requested or introduced.


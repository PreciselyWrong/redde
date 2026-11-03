# Redde for Android — notes for Claude Code

This repository is being turned from the iOS app Redde (Swift) into an Android app (Kotlin,
Jetpack Compose). The Swift code is gone from the working tree; the iOS source the port follows is
commit **`37a2db7`**. Read any iOS file without checking it out:

```sh
git show 37a2db7:Echo/Services/Transport/HermesSessionsTransport.swift
git ls-tree -r --name-only 37a2db7 | grep -i <name>
```

Status, phase by phase, and where each iOS area goes: **`docs/PORTING.md`** (keep it up to date
with every step). The full plan (phases 0–10, stack, risks) is summarized there and in the README.
`docs/ARCHITECTURE.md` still describes the iOS app: its Connections and Conversations sections are
the protocol spec.

## Build and test

```sh
./gradlew test                          # everything (needs the Android SDK + Google Maven)
./gradlew -Predde.jvmOnly=true test     # only the pure-JVM core/* modules (Maven Central only)
./gradlew :app:assembleDebug            # debug APK
```

- JDK 17+; Android SDK platform 36 via `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.
- The cloud sessions this was started in could not reach `dl.google.com`, so **the Android
  modules (`app`, `core/data`, `feature/*`) have never been compiled**. First thing on a machine
  with the SDK: `./gradlew :app:assembleDebug` and fix what breaks. The versions in
  `gradle/libs.versions.toml` for AGP (8.13.0), Compose BOM, AndroidX and Tink were picked from
  memory and may need bumping.
- Maven Central sometimes answers 429 under parallel load; `--max-workers=1` helps.

## Modules

| Module | Kind | Holds |
| --- | --- | --- |
| `core/model` | JVM | Data the app works with: `Message`, `Attachment`, `TurnEvent`, `TurnRequest`, `Interrupt`, `SetupCode`, `CustomHeader`, `Pangolin`, `AgentNotes.kt` (notes sent to the agent), JSON helpers (`Json.kt`) |
| `core/transport` | JVM | SSE (`Sse.kt`), `StreamingHttp` (OkHttp → `Flow`), `TransportError`, `HermesTransport` interface, `ChatCompletionsTransport`; Hermes API and Dashboard transports to come |
| `core/markdown` | JVM | Block parser, inline span parser, path links, syntax highlighter |
| `core/crypto` | JVM | `PushSeal` (X25519/HKDF/AES-GCM, byte-compatible with `companion/hermes-plugin`), `PushOffer` |
| `core/data` | Android | (empty) conversation store, settings, Keystore-backed secrets — phase 2 |
| `feature/voice`, `feature/phonetools`, `feature/terminal` | Android | (empty) phases 5–8; voice resources already moved here |
| `app` | Android | Minimal Compose shell, manifest, icon, 18 languages of strings |

Package root: `ch.nicolab.redde` (application ID too).

## Conventions

- **Port faithfully, with the tests.** Each Swift file's behaviour moves 1:1; its `EchoTests/*`
  cases move to the Kotlin module's `src/test` (kotlin.test + JUnit 5, MockWebServer for HTTP).
  Name the iOS source in a KDoc line or in the test class comment.
- **Words sent to the model stay byte for byte the iOS ones** (notes, instructions, client hint):
  the app reads back messages the iOS app wrote, and `ReplyLanguage.stripNote` relies on them.
- **No Android types in `core/*`.** Strings shown to the person are not built there: errors are
  typed (`TransportError`, `SetupCode.ParseError`, `AttachmentError`) and the app words them with
  `R.string.*`. The English iOS text → resource name map is `docs/porting/strings-map.json`.
- JSON: `ReddeJson` for (de)serialization; read server frames field by field with the
  `JsonElement?` accessors in `core/model/.../Json.kt` (`p["x"].str`, `.int`, `.bool`, `.arr`).
  Stored records keep the iOS format (ISO-8601 dates without fraction, upper-case UUIDs).
- KDoc and code comments in English, explaining why, as in the iOS source.
- Commit per step, push to the working branch; update `docs/PORTING.md`.

## Known debt

- `core/crypto/PushOffer` re-implements a little of `SetupCode`'s link reading: fold it onto `core/model`.
- Hilt not wired yet (planned for phase 2).
- The setup-link web host is still `redde.goosehouse.org` (`SetupCode.WEB_HOSTS`); the push relay is
  Goosehouse's and speaks APNs only — an FCM branch on your own Cloudflare account is phase 10.
- `scripts/hermes-lab/lab.sh` and `scripts/terminal-lab/lab.sh` still drive the iOS simulator.
- `LICENSE.md`: the Redde name and icons are not licensed for other products; a published fork
  needs its own name and icon.

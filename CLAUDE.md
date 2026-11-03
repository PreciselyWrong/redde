# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

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
./gradlew :app:lintDebug                # Android lint (what CI runs, with test and assembleDebug)

# One module, one class, one test
./gradlew -Predde.jvmOnly=true :core:transport:test
./gradlew -Predde.jvmOnly=true :core:transport:test --tests 'ch.nicolab.redde.transport.HermesServeTest'
./gradlew -Predde.jvmOnly=true :core:model:test --tests '*SetupCodeTest.readsEveryParameter'
```

`-Predde.jvmOnly=true` drops the Android modules and Google's Maven repository from the build
(`settings.gradle.kts`, root `build.gradle.kts`); without it the root build needs AGP to resolve.
Test results land in `core/<module>/build/test-results/test/*.xml`.

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
| `core/transport` | JVM | SSE, `StreamingHttp` (OkHttp → `Flow`), `TransportError`, `HermesTransport`; transports `ChatCompletionsTransport`, `HermesSessionsTransport` + `HermesSessionsApi` + `HermesRuns`, `HermesServeClient` + `HermesServeTransport`; `ConversationHistory`, `BotMessages`, `SessionPage` |
| `core/markdown` | JVM | Block parser, inline span parser, path links, syntax highlighter |
| `core/crypto` | JVM | `PushSeal` (X25519/HKDF/AES-GCM, byte-compatible with `companion/hermes-plugin`), `PushOffer` |
| `core/store` | JVM | `ConversationStore` (iOS JSON files), `ConversationRecord`/`Summary`, `OutboxItem`, `HermesServer`, `SecretStore` interface and account names, `FolderAttachmentFiles` |
| `core/data` | Android | (empty) Keystore-backed `SecretStore`, DataStore settings — phase 2 |
| `feature/voice`, `feature/phonetools`, `feature/terminal` | Android | (empty) phases 5–8; voice resources already moved here |
| `app` | Android | Minimal Compose shell, manifest, icon, 18 languages of strings |

Package root: `ch.nicolab.redde` (application ID too).

## How it fits together

- **A turn** is a `TurnRequest` (core/model) handed to a `HermesTransport.stream()`, which returns a
  cold `Flow<TurnEvent>`: text/reasoning deltas, tool start/finish, todos, subagents, interrupts
  (approval, clarify, sudo, secret), usage, then `Done`. Cancelling the collection stops the turn on
  the host unless `TurnRequest.departure` was marked left. Every transport speaks this one event
  vocabulary; the (not yet ported) conversation state machine only consumes it.
- **Three transports, three protocols** (all in `core/transport`):
  - `ChatCompletionsTransport`: stateless OpenAI-compatible SSE; the whole history is sent each turn.
  - `HermesSessionsTransport`: the Hermes API (port 8642, bearer key, `/p/<profile>/` for profiles).
    A turn is a *run* (`HermesRuns`: `POST v1/runs`, SSE events with `Last-Event-ID`, status polling
    for 0.21.x), with `api/sessions/{id}/chat/stream` as the fallback when the gateway has no runs.
    Notes go in the run's `instructions` (`CLIENT_HINT` first).
  - `HermesServeTransport` over `HermesServeClient`: the Hermes Dashboard (port 9119), JSON-RPC 2.0
    on one shared WebSocket. The client owns login (password cookie or the no-sign-in page token,
    `SessionPage`), the ws-ticket, ping/reconnect, the stored→runtime session id map, and host
    *requests* (approvals etc. arrive as server→client JSON-RPC calls, answered on their own id;
    0.21.0 sent `<kind>.request` notifications answered by `<kind>.respond` — both are handled).
    The Dashboard takes no instructions, so notes ride on the message text (`AgentNotes.kt` `note()`),
    and `ReplyLanguage.stripNote` takes them off when transcripts are read back.
- **Transcripts from the server** become `Message`s through `ConversationHistory` (Dashboard rows vs
  the API's `StoredMessage`), with `BotMessages` recognising bot-to-bot messages, background
  processes, steers and routines. `SessionBackend` puts list/rename/pin/archive/delete/fork behind
  one interface for either Hermes backend.
- **Local state** (`core/store`): `ConversationStore` writes the same per-conversation JSON + index
  as the iOS app; attachment bytes live outside the records (`AttachmentFiles.current`).
- **Streaming plumbing**: `StreamingHttp.run` turns an SSE response into events through a per-transport
  `decode(SseEvent) -> Decoded`; a failure before headers is `TransportError.Unreachable` (the message
  is held and retried), after headers `StreamLost` (not resent, the host may have it).
- **Tests** run transports against MockWebServer, including a stand-in Dashboard with a WebSocket
  (`HermesServeTest`); `core/crypto` must keep passing the vectors produced by the Python plugin.

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

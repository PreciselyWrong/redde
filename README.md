# Redde for Android

An Android phone and tablet client for [Hermes](https://github.com/NousResearch/hermes-agent), the
open-source AI agent you run on your own machine, or for any OpenAI-compatible model server. Talk or
type, watch the agent think and use its tools, approve what it wants to run, and manage its
sessions, projects, skills, cron jobs and Kanban board from your phone.

This is the Kotlin port of the iOS app [Redde](https://redde.goosehouse.org). The port is under
way: what is done, and where each part of the iOS app goes, is tracked in
[docs/PORTING.md](docs/PORTING.md). The last iOS commit it was ported from is `37a2db7`.

No account and no analytics: your conversations go only to the server you set up, and credentials
stay on the device, encrypted with a key held by the Android Keystore.

## Requirements

- Android 10 (API 29) or later; on-device speech recognition needs Android 12.
- A backend, reached by one of three connections:

| Connection | Server | Best for |
| --- | --- | --- |
| **Hermes Dashboard** | `hermes serve` (port 9119), dashboard username and password, or a browser sign-in | Everything: live reasoning, approvals, slash commands, projects, Kanban, file editing |
| **Hermes API** | The Hermes gateway's API server (port 8642), `API_SERVER_KEY` | The shared session ledger with a single key |
| **OpenAI-compatible** | llama.cpp, llama-swap, vLLM, Ollama or a hosted provider | Talking straight to a model, with no agent |

Profiles need Hermes 0.21 or later. Over the Hermes API a named profile also needs
`gateway.multiplex_profiles` and that profile's own `API_SERVER_KEY`.

A Dashboard that signs you in with Google or another identity provider has no password to type:
tap **Sign in with a browser** (Hermes 0.21 or later). A server behind a reverse proxy that asks
for a header of its own takes it under Settings → Connection details → Custom headers, next to
the Cloudflare Access service token. Behind [Pangolin](https://pangolin.net), make a Link to the
resource and paste its access token under Pangolin there (`id.token`, or the link with `p_token=`):
one for the Dashboard and one for the Hermes API, or one for both.

Profiles need Hermes 0.21 or later. Over the Hermes API a named profile also needs
`gateway.multiplex_profiles` and that profile's own `API_SERVER_KEY`.

## Setup codes

Instead of typing an address and a key on the phone, hand Redde a setup code: a link, or a QR code
of it.

```sh
scripts/setup-code.py --name Home --dashboard http://hermes.home.example:9119 --user redde
```

The script asks for the password without showing it and prints the link, plus its QR code when
[`qrencode`](https://fukuchi.org/works/qrencode/) is installed. In Redde's setup screen choose
**Scan a setup code** or **Paste a setup link**; tapping the link in a message opens it too. Redde shows what the code sets and where it points, and
saves nothing until you agree. It never overwrites a server that is already set up: the code is
added beside it.

Once one device is set up, Settings › Connection › **Set up another device** shows its code.
Passwords and keys are left out until you ask for them, which takes the fingerprint, face or screen lock.

The link is `https://redde.goosehouse.org/connect#` followed by any of these, percent-encoded:

| Parameter | Meaning |
| --- | --- |
| `name` | What the server is called in Redde |
| `dashboard`, `user`, `password` | Hermes Dashboard address and login |
| `api`, `key` | Hermes API server address and `API_SERVER_KEY` |
| `profile`, `profile-key` | A Hermes profile, and its own API key if it has one |
| `access-id`, `access-secret` | A Cloudflare Access service token |
| `pangolin`, `pangolin-api` | Pangolin Link access tokens (`id.token`) for the Dashboard and the Hermes API (`setup-code.py --pangolin`) |
| `header` | A header your reverse proxy asks for, as `Name: value`; may be repeated (`setup-code.py --header Name`) |
| `model-url`, `model-key`, `model` | An OpenAI-compatible endpoint |
| `use` | `dashboard`, `api` or `model`: which connection Redde talks to (default: the first in the code) |

It is a web address so that Android opens it in Redde wherever it is tapped (a verified App Link), and so that a device without the app gets a page saying what to do. The
connection comes after the `#`, the part of an address a browser never sends, so the site doesn't
see it; the page is a static file ([`companion/website`](companion/website)) whose script only
hands the link to the app. To involve no website at all, use the app's own form, with the same
parameters after `redde://connect?` (`scripts/setup-code.py --app-link`).

A code with a password in it is as good as the password. Show it to your own devices, not in a
screenshot or a chat.

## Building

Requires JDK 17 or later and the Android SDK (platform 36). Point `local.properties` at the SDK
(`sdk.dir=…`) or set `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The application ID is `ch.nicolab.redde`.

### Tests

```sh
./gradlew test
```

The protocol, parsing and crypto code lives in plain Kotlin/JVM modules (`core/*`) whose tests run
without Android. On a machine with no Android SDK or no access to Google's Maven repository, build
and test those alone:

```sh
./gradlew -Predde.jvmOnly=true test
```

## Repository

| Path | What's there |
| --- | --- |
| `app/` | The Android app: activities, Compose screens, Android services |
| `core/model/` | Messages, reply cards, setup codes, servers: the data the app works with (JVM) |
| `core/transport/` | The three connections: Hermes Dashboard (WebSocket JSON-RPC), Hermes API (runs and SSE), OpenAI-compatible (JVM) |
| `core/markdown/` | The markdown parser and syntax highlighter (JVM) |
| `core/crypto/` | End-to-end encryption of push notifications, compatible with the `redde-push` plugin (JVM) |
| `core/store/` | Conversations on disk in the iOS format, saved servers, the secret store's account names (JVM) |
| `core/data/` | Android storage: Keystore-encrypted secrets, settings |
| `feature/voice/`, `feature/phonetools/`, `feature/terminal/` | Voice mode, the agent's phone tools, the SSH terminal |
| `companion/` | The website, the push-notification relay Worker and the Hermes plugin that feeds it |
| `scripts/` | Setup codes, the Hermes lab for testing against stock gateways, stand-in servers |
| `design/` | Icon sources |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [porting status](docs/PORTING.md), push design, privacy policy |

## Contributing

Pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md); first-time contributors sign the
[CLA](CLA.md) with a one-line comment when the bot asks. Report security problems privately, as
described in [SECURITY.md](SECURITY.md).

## License

[FSL-1.1-MIT](LICENSE.md), the Functional Source License. You can read, use, modify and self-host
Redde for any purpose except offering a competing commercial product. Each release becomes MIT on
its second anniversary.

The Redde name and app icons are not licensed for use in other products. Bundled third-party code
keeps its own license: KaTeX and Mermaid (MIT), KaTeX fonts (OFL).

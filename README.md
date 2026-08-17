# Lydex plugin examples

Reference implementations and starter templates for the two Lydex plugin
APIs. A plugin is just a separate Android app that Lydex discovers on the
device — bring your own **streaming source** or your own **DSP effect**.

> **Preview.** These plugin APIs work today but are still a v1 preview — the
> contract may change before it is finalised. Pin the version you build
> against. Full guides: <https://lydexplayer.app/en/developers>

## What's here

| Path | What it is |
| --- | --- |
| `stream_plugin_api/` | The shared contract (AIDL + Kotlin) every stream provider compiles against. |
| `subsonic/` | **Reference stream provider** — a complete, shipping Subsonic plugin. Works with Navidrome, Airsonic, Gonic, and any Subsonic-compatible server. |
| `dsp-example/` | **DSP template** — a minimal WebAssembly effect (output trim + tanh warmth) plus its APK wrapper. |

## Stream provider plugins

A stream provider adds a music source. It exposes a small AIDL control plane
(`capabilities` / `browse` / `resolve`); the Lydex engine does the fetching,
decoding and bit-perfect playback. `resolve()` returns a `StreamHandle` telling
the engine how to get the bytes — a direct URL is the common case.

Start from `subsonic/`: it covers auth, browse, search and direct-URL delivery.
It depends only on `stream_plugin_api`. Build the APK:

```bash
./gradlew :subsonic:assembleRelease
```

## DSP plugins

A DSP plugin adds an audio effect. You compile a `.wasm` module that exports
the Lydex DSP ABI and process audio in place through a shared interleaved-f64
buffer; the manifest declares the sliders and presets.

Build the WebAssembly, then the APK:

```bash
rustup target add wasm32-unknown-unknown
cd dsp-example/rust && cargo build --release --target wasm32-unknown-unknown
cd ../.. && ./gradlew :dsp-example:assembleRelease
```

The Gradle build stages the built `.wasm` into the APK's `assets/`.

## Requirements

- JDK 17, Android SDK (compileSdk 36), Gradle wrapper included.
- For DSP: a Rust toolchain with the `wasm32-unknown-unknown` target.

## License

MIT — see [LICENSE](LICENSE).

# Ramflux Android

Ramflux Android is the native Android client for Ramflux, built with Kotlin and
Gradle. It embeds the Ramflux core SDK through its C ABI.

This repository contains only the AGPL client. The Ramflux core SDK and protocol
crates live in `ramfluxhq/ramflux`.

The bridge libraries are `:core:sdk` and `:core:data`. They contain no UI. See
[bridge architecture and build instructions](docs/bridge.md) for Kotlin tests,
the cargo-ndk/JNI build, cancellation, event resync and the SDK capability boundary.
Physical-device and server acceptance are separate from compilation and JVM tests.

## License

AGPL-3.0-or-later with the Ramflux App Store Exception. See `LICENSE`,
`LICENSING.md` and `NOTICE`.

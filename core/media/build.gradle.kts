// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.compose") }
dependencies {
    implementation(project(":core:data"))
    // BLAKE3 for the attachment.prepare.finish digest. Bouncy Castle Licence (MIT), compatible with AGPL-3.0.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Video: on-device transcoding (Transformer with its effect pipeline) and playback from app-private
    // files (ExoPlayer, Compose surface). AndroidX Media3, Apache-2.0, compatible with AGPL-3.0.
    api("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    api("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}

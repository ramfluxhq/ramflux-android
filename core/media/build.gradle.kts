// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.compose") }
dependencies {
    implementation(project(":core:data"))
    // BLAKE3 for the attachment.prepare.finish digest. Bouncy Castle Licence (MIT), compatible with AGPL-3.0.
    implementation(libs.bouncycastle.prov)
    // Video: on-device transcoding (Transformer with its effect pipeline) and playback from app-private
    // files (ExoPlayer, Compose surface). AndroidX Media3, Apache-2.0, compatible with AGPL-3.0.
    api(libs.media3.common)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    api(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}

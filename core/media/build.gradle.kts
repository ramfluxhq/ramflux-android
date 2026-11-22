// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.compose") }
dependencies {
    implementation(project(":core:data"))
    // BLAKE3 for the attachment.prepare.finish digest. Bouncy Castle Licence (MIT), compatible with AGPL-3.0.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}

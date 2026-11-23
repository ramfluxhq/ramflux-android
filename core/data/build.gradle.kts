// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.gradle.LibraryExtension

plugins { id("ramflux.android.library") }
extensions.configure<LibraryExtension> {
    buildFeatures.buildConfig = true
    // Debug builds may reach a local test node over plain HTTP (docs/bridge.md, "Local test node"); release
    // builds never can. A build-type constant, not a setting anyone can change at run time.
    buildTypes.getByName("debug").buildConfigField("boolean", "LOCAL_NODE_HTTP", "true")
    buildTypes.getByName("release").buildConfigField("boolean", "LOCAL_NODE_HTTP", "false")
}
dependencies {
    api(project(":core:sdk"))
    // BLAKE3 for the SDK's direct conversation id (DirectConversationId); the same library :core:media uses.
    implementation(libs.bouncycastle.prov)
}

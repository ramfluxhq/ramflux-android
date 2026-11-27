// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins {
    id("ramflux.android.compose")
    id("ramflux.android.testing")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    // The card renderer: shared by the chat timeline and the realrun card gallery, so it is not a feature implementation.
    api(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.serialization.json)
}

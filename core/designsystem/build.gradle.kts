// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins {
    id("ramflux.android.designsystem")
    id("ramflux.android.testing")
}

dependencies {
    api(libs.collections.immutable)
    implementation(libs.compose.icons)
}

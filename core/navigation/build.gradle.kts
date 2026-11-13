// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins {
    id("ramflux.android.compose")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(libs.navigation.runtime)
    api(libs.navigation.ui)
    api(libs.serialization.json)
}

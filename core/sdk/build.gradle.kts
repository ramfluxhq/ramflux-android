// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.sdk"); alias(libs.plugins.kotlin.serialization) }
dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.coroutines.android)
}

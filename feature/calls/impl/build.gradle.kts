// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.feature") }
dependencies {
    implementation(libs.compose.icons)
    api(project(":feature:calls:api"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.collections.immutable)
}

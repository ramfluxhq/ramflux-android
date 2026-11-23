// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.feature") }
dependencies {
    api(project(":feature:chat:api"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(libs.collections.immutable)
    // Platform emoji picker for the reaction "+" (board MOB-B03). AndroidX, Apache-2.0, compatible with AGPL-3.0.
    implementation(libs.emoji2.picker)
}

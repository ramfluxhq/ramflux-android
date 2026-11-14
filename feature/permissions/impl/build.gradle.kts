// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.feature") }
dependencies {
    api(project(":feature:permissions:api"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.collections.immutable)
}

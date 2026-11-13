// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.testing") }
dependencies {
    testImplementation(project(":feature:conversations:impl"))
    testImplementation(project(":core:data"))
    testImplementation(project(":core:designsystem"))
    testImplementation(libs.collections.immutable)
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.app") }
dependencies {
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding:impl"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:app-shell:impl"))
}

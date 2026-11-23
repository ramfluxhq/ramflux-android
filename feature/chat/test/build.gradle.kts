// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.testing") }
// Board content shared with the app's board captures.
android.sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/fixtures/chat/kotlin"))
dependencies {
    testImplementation(project(":feature:chat:impl"))
    testImplementation(project(":core:data"))
    testImplementation(project(":core:designsystem"))
    testImplementation(libs.collections.immutable)
}

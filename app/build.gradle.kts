// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.app") }
android {
    bundle { language { enableSplit = false } }
    testOptions.unitTests.isIncludeAndroidResources = true
}
dependencies {
    implementation(project(":feature:groups:impl"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.test)
    testImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.test.manifest)
    implementation(project(":core:data"))
    implementation(project(":feature:settings:impl"))
    implementation(project(":feature:contacts:impl"))
    implementation(project(":feature:chat:impl"))
    implementation(project(":feature:conversations:impl"))
    implementation(project(":feature:onboarding:impl"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:app-shell:impl"))
}

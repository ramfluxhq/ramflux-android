// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.app") }
android {
    bundle { language { enableSplit = false } }
    // The locale config lists exactly the locales that have resources (res/resources.properties names
    // the default), so adding a language is a resources-only change.
    androidResources { generateLocaleConfig = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/screenshot/kotlin"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/fixtures/conversations/kotlin"))
}
dependencies {
    implementation(project(":feature:permissions:impl"))
    implementation(project(":feature:cards:impl"))
    implementation(project(":feature:tasks:impl"))
    implementation(project(":feature:agents:impl"))
    implementation(project(":feature:calls:impl"))
    implementation(project(":feature:groups:impl"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
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

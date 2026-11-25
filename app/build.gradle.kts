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
    sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/fixtures/chat/kotlin"))
}
// The edition selects the push modules the app links (push/README.md); only `international` exists so far.
val edition = providers.gradleProperty("ramflux.edition").orElse("international").get()
dependencies {
    implementation(project(":push:impl"))
    when (edition) {
        "international" -> {
            implementation(project(":push:international:compat"))
            implementation(project(":push:international:fcm"))
        }
        else -> throw GradleException("edition not implemented: $edition (ramflux.edition must be international)")
    }
    implementation(project(":feature:permissions:impl"))
    implementation(project(":feature:cards:impl"))
    implementation(project(":feature:tasks:impl"))
    implementation(project(":feature:agents:impl"))
    implementation(project(":feature:calls:impl"))
    implementation(project(":feature:groups:impl"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // The opt-in real-run suite (org.ramflux.android.realrun, scripts/realrun.sh); no CI task runs it.
    androidTestImplementation(libs.androidx.test.uiautomator)
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

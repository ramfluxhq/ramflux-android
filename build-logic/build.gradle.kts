// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { `kotlin-dsl` }
repositories { google(); mavenCentral(); gradlePluginPortal() }
dependencies { implementation(libs.android.gradle); implementation(libs.kotlin.gradle)
    implementation(libs.compose.gradle)
    implementation(libs.hilt.gradle)
    runtimeOnly(libs.ksp.gradle)
    implementation(libs.ktlint.gradle)
    implementation(libs.roborazzi.gradle) }
gradlePlugin {
    plugins { register("androidLibrary") {
        id = "ramflux.android.library"
        implementationClass = "AndroidLibraryPlugin"
    }
    register("androidSdk") {
        id = "ramflux.android.sdk"
        implementationClass = "AndroidSdkPlugin"
    }
    register("androidPackaging") {
        id = "ramflux.android.packaging"
        implementationClass = "AndroidPackagingPlugin"
    }
    register("androidDesignSystem") { id = "ramflux.android.designsystem"; implementationClass = "AndroidDesignSystemPlugin" }
    register("androidCompose") { id = "ramflux.android.compose"; implementationClass = "AndroidComposePlugin" }
    register("androidFeature") { id = "ramflux.android.feature"; implementationClass = "AndroidFeaturePlugin" }
    register("androidTesting") { id = "ramflux.android.testing"; implementationClass = "AndroidTestingPlugin" }
    register("androidApp") { id = "ramflux.android.app"; implementationClass = "AndroidAppPlugin" }
    }
}

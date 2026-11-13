// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { `kotlin-dsl` }
repositories { google(); mavenCentral(); gradlePluginPortal() }
dependencies { implementation(libs.android.gradle); implementation(libs.kotlin.gradle) }
gradlePlugin {
    plugins { register("androidLibrary") {
        id = "ramflux.android.library"
        implementationClass = "AndroidLibraryPlugin"
    }
    register("androidSdk") {
        id = "ramflux.android.sdk"
        implementationClass = "AndroidSdkPlugin"
    } }
}

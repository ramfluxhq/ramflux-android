// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins {
    id("ramflux.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}
apply(plugin = "org.jlleitschuh.gradle.ktlint")
dependencies {
    api(project(":push:api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

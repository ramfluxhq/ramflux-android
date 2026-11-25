// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins {
    id("ramflux.android.library")
    alias(libs.plugins.kotlin.serialization)
}
apply(plugin = "org.jlleitschuh.gradle.ktlint")
dependencies {
    api(project(":core:navigation"))
    api(libs.coroutines.core)
}

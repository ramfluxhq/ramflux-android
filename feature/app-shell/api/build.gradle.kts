// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

plugins { id("ramflux.android.library"); alias(libs.plugins.kotlin.serialization) }
dependencies {
    api(project(":core:navigation"))
    api(project(":core:data"))
}

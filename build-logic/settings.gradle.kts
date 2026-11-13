// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

dependencyResolutionManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"

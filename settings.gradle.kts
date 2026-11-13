// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ramflux-android"
include(":core:sdk", ":core:data")
include(":fixtures:packaging")

include(":app", ":core:designsystem", ":core:navigation")
include(":feature:app-shell:api", ":feature:app-shell:impl", ":feature:app-shell:test")

include(":feature:onboarding:api", ":feature:onboarding:impl", ":feature:onboarding:test")

include(":feature:conversations:api", ":feature:conversations:impl", ":feature:conversations:test")

include(":feature:chat:api", ":feature:chat:impl", ":feature:chat:test")

include(":feature:settings:api", ":feature:settings:impl", ":feature:settings:test")

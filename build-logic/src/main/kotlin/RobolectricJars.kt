// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named

/** Point Robolectric at the prefetched jars and forbid a test-time download. */
fun Project.offlineRobolectric() {
    tasks.withType(Test::class.java).configureEach {
        dependsOn(rootProject.tasks.named("prefetchRobolectricJars"))
        systemProperty("robolectric.offline", "true")
        systemProperty(
            "robolectric.dependency.dir",
            rootProject.layout.projectDirectory.dir("robolectric-jars").asFile.absolutePath,
        )
    }
}

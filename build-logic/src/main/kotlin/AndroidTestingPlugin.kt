// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType

class AndroidTestingPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ramflux.android.compose")
        pluginManager.apply("io.github.takahirom.roborazzi")
        extensions.getByType<LibraryExtension>().testOptions.unitTests.isIncludeAndroidResources = true
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf("compose-test", "robolectric", "roborazzi", "roborazzi-compose", "androidx-test-core").forEach {
            dependencies.add("testImplementation", libs.findLibrary(it).get())
        }
        dependencies.add("debugImplementation", libs.findLibrary("compose-test-manifest").get())
        tasks.withType(Test::class.java).configureEach {
            systemProperty("robolectric.graphicsMode", "NATIVE")
            if (System.getenv("CI") != "true") exclude("**/*ScreenshotTest*")
        }
        Unit
    }
}

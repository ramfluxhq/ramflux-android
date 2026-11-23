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
        extensions.getByType<LibraryExtension>().apply {
            testOptions.unitTests.isIncludeAndroidResources = true
            // Shared screenshot parameter lists (sizes, locales, themes, font scales) for every test module.
            sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/screenshot/kotlin"))
        }
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf("compose-test", "robolectric", "roborazzi", "roborazzi-compose", "androidx-test-core").forEach {
            dependencies.add("testImplementation", libs.findLibrary(it).get())
        }
        dependencies.add("debugImplementation", libs.findLibrary("compose-test-manifest").get())
        offlineRobolectric()
        tasks.withType(Test::class.java).configureEach {
            systemProperty("robolectric.graphicsMode", "NATIVE")
            // Deliberate: the screenshot matrix renders hundreds of frames, so local `test` runs skip it.
            // CI sets CI=true; run `CI=true ./gradlew <module>:recordRoborazziDebug` to render locally.
            if (System.getenv("CI") != "true") exclude("**/*ScreenshotTest*")
            // The screenshot tests write their captures below build/outputs. Declaring the directories makes
            // them outputs of the task, so a build-cache hit restores the captures instead of skipping them.
            outputs.dir(layout.buildDirectory.dir("outputs/roborazzi"))
            outputs.dir(layout.buildDirectory.dir("outputs/roborazzi-variants"))
        }
        Unit
    }
}

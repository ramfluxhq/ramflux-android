// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<LibraryExtension> {
            namespace = "org.ramflux." + path.trim(':').replace(':', '.')
            compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
            ndkVersion = libs.findVersion("ndk").get().requiredVersion
            defaultConfig {
                minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            packaging.jniLibs.useLegacyPackaging = false
            lint {
                warningsAsErrors = true
                abortOnError = true
                // Keep upgrade advice visible without making pinned builds depend on release timing.
                informational += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable")
            }
            if (path in setOf(":core:sdk", ":core:data")) {
                sourceSets.getByName("test").java.srcDir(rootProject.file("test-support/kotlin"))
            }
            testOptions.unitTests.all { it.maxHeapSize = "1g" }
        }
        extensions.configure<KotlinAndroidProjectExtension> { jvmToolchain(17) }
        dependencies.add("testImplementation", libs.findLibrary("junit").get())
        dependencies.add("testImplementation", libs.findLibrary("coroutines-test").get())
        Unit
    }
}

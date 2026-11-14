// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.api.dsl.ApplicationExtension
import groovy.json.JsonSlurper
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        listOf("com.android.application", "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.plugin.compose", "com.google.devtools.ksp", "com.google.dagger.hilt.android", "org.jlleitschuh.gradle.ktlint").forEach { pluginManager.apply(it) }
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.getByType<ApplicationExtension>().apply {
            namespace = "org.ramflux.android"
            compileSdk = 36
            defaultConfig { applicationId = "org.ramflux.android"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; ndk.abiFilters.add("arm64-v8a") }
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            buildFeatures.compose = true
            buildFeatures.buildConfig = true
            val descriptor = JsonSlurper().parse(rootProject.file("sdk-contract/descriptor.json")) as Map<*, *>
            defaultConfig.buildConfigField("String", "MESSAGE_DESCRIPTOR", "\"${descriptor["blake3"]}\"")
            compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            buildTypes.getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
            buildTypes.getByName("debug").isPseudoLocalesEnabled = true
            packaging.jniLibs.useLegacyPackaging = false
            lint { warningsAsErrors = true; informational += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency") }
        }
        extensions.getByType<KotlinAndroidProjectExtension>().jvmToolchain(17)
        dependencies.add("implementation", dependencies.platform(libs.findLibrary("compose-bom").get()))
        listOf("activity-compose", "compose-material", "lifecycle-process", "hilt-android", "hilt-compose", "navigation-runtime", "navigation-ui", "lifecycle-compose", "lifecycle-viewmodel").forEach { dependencies.add("implementation", libs.findLibrary(it).get()) }
        dependencies.add("ksp", libs.findLibrary("hilt-compiler").get())
        Unit
    }
}

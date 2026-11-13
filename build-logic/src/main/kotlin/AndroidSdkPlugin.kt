// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

abstract class NativeProfileTask : DefaultTask() {
    @get:Input abstract val profile: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val value = profile.get()
        require(value in setOf("release", "dev"))
        val marker = outputDirectory.get().file("ramflux-native-profile.txt").asFile
        marker.parentFile.mkdirs(); marker.writeText(value + "\n")
    }
}

class AndroidSdkPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ramflux.android.library")
        val nativeOutput = layout.buildDirectory.dir("generated/native")
        val generateProfile = tasks.register<NativeProfileTask>("generateNativeProfile") {
            profile.set(providers.environmentVariable("RAMFLUX_NATIVE_PROFILE").orElse("release"))
            outputDirectory.set(layout.buildDirectory.dir("generated/nativeProfileAssets"))
        }
        extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(generateProfile) { it.outputDirectory }
        }
        val buildNative = tasks.register<Exec>("buildNative") {
            dependsOn(generateProfile)
            group = "build"
            description = "Build the pinned shared C ABI and JNI with cargo-ndk and NDK r28"
            commandLine("bash", rootProject.file("scripts/build-native.sh"), nativeOutput.get().asFile)
            outputs.upToDateWhen { false }
        }
        extensions.getByType<LibraryExtension>().sourceSets.getByName("main")
            .jniLibs.srcDir(nativeOutput.map { it.dir("jniLibs") })
        tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
            dependsOn(buildNative)
        }
    }
}

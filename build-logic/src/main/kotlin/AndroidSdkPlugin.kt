// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

class AndroidSdkPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("ramflux.android.library")
        val nativeOutput = layout.buildDirectory.dir("generated/native")
        val buildNative = tasks.register<Exec>("buildNative") {
            group = "build"
            description = "Build the shared C ABI and JNI with cargo-ndk and NDK r28"
            commandLine("bash", rootProject.file("scripts/build-native.sh"), nativeOutput.get().asFile)
            // Product sources live outside this build. Always verify them when assembling.
            outputs.upToDateWhen { false }
        }
        extensions.getByType<LibraryExtension>().sourceSets.getByName("main").apply {
            jniLibs.srcDir(nativeOutput.map { it.dir("jniLibs") })
            assets.srcDir(nativeOutput.map { it.dir("assets") })
        }
        tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
            dependsOn(buildNative)
        }
        tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
            dependsOn(buildNative)
        }
    }
}

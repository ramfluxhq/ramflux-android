// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

import com.android.build.gradle.LibraryExtension
import groovy.json.JsonSlurper

plugins {
    id("ramflux.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}
apply(plugin = "org.jlleitschuh.gradle.ktlint")

/**
 * The Firebase client entries of the `google-services.json` that `ramflux.firebase.configFile` names, as
 * `package,appId,apiKey,projectId,senderId` records joined by `;`. The file stays outside the repository and the Google
 * services plugin is not applied; no property, an unreadable file or an entry with unexpected characters leaves the
 * entry out, and the app then reports background delivery as not configured. Values are never printed.
 */
fun firebaseClients(): String {
    val path = providers.gradleProperty("ramflux.firebase.configFile").orNull?.takeIf { it.isNotBlank() } ?: return ""
    val safe = Regex("[A-Za-z0-9:_.\\-]+")
    return try {
        val root = JsonSlurper().parse(rootProject.file(path)) as Map<*, *>
        val info = root["project_info"] as Map<*, *>
        val projectId = info["project_id"] as String
        val senderId = info["project_number"] as String
        (root["client"] as List<*>).mapNotNull { entry ->
            val client = entry as Map<*, *>
            val clientInfo = client["client_info"] as Map<*, *>
            val fields = listOf(
                ((clientInfo["android_client_info"] as Map<*, *>)["package_name"] as String),
                clientInfo["mobilesdk_app_id"] as String,
                ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"] as String,
                projectId,
                senderId
            )
            if (fields.all { it.matches(safe) }) fields.joinToString(",") else null
        }.joinToString(";")
    } catch (_: Exception) {
        logger.warn("ramflux.firebase.configFile is set but cannot be read; background delivery stays not configured")
        ""
    }
}

extensions.configure<LibraryExtension> {
    buildFeatures.buildConfig = true
    defaultConfig.buildConfigField("String", "FIREBASE_CLIENTS", "\"" + firebaseClients() + "\"")
}
dependencies {
    api(project(":push:api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}

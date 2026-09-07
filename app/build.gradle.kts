/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.osfans.trime.app-convention")
    id("com.osfans.trime.native-app-convention")
    id("com.osfans.trime.data-checksums")
    id("com.osfans.trime.native-cache-hash")
    id("com.osfans.trime.opencc-data")
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.osfans.trime"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.osfans.trime"
        minSdk = 21
        targetSdk = 35
        versionCode = 20260901
        versionName = "3.3.12"

        multiDexEnabled = true
        buildConfigField("String", "BUILDER", "\"${project.builder}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", project.buildTimestamp)
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${project.buildCommitHash}\"")
        buildConfigField("String", "BUILD_GIT_REPO", "\"${project.buildGitRepo}\"")
        buildConfigField("String", "BUILD_VERSION_NAME", "\"${project.buildVersionName}\"")
    }

    base {
        // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
        archivesName = "${android.defaultConfig.applicationId}-$buildVersionName"
    }

    buildTypes {
        release {
            // trime-9key: NOT USABLE YET. This variant builds and installs
            // (signed, own package, own name), but its IME service never puts
            // a keyboard on screen -- unresolved, and the reason the project
            // still ships the debug variant. See the minification note below;
            // turning minification off removes most of the symptoms but not
            // the last one. The speed this was meant to buy was largely
            // captured already by the logging and schema-reload fixes.
            //
            // this fork ships alongside upstream Trime rather
            // than replacing it (own app name, own /sdcard/trime9key data
            // dir), so it needs its own identity too -- the inherited
            // com.osfans.trime would collide with an installed upstream
            // build and, signed with a different key, simply refuse to
            // install.
            applicationIdSuffix = ".t9"
            // Minification is OFF on purpose. Trime resolves themes, colors,
            // key icons and schema assets BY NAME at runtime, so R8 and the
            // resource shrinker cannot see those references: a minified build
            // installs and launches, but every runtime lookup returns
            // "Invalid resource ID 0x00000000" and the keyboard never appears.
            // Making it work needs real keep-rule engineering, not a flag.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                project.signKeyFile?.let {
                    signingConfigs.create("release") {
                        storeFile = it
                        storePassword = project.signKeyStorePwd
                        keyAlias = project.signKeyAlias
                        keyPassword = project.signKeyPwd
                    }
                }

            // this fork is Trime9Key in both variants; the inherited
            // release name "Trime" would be indistinguishable from upstream.
            resValue("string", "trime_app_name", "@string/app_name_debug")
        }
        debug {
            applicationIdSuffix = ".debug"

            resValue("string", "trime_app_name", "@string/app_name_debug")
        }
        all {
            // remove META-INF/version-control-info.textproto
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            // https://youtrack.jetbrains.com/issue/KT-55947
            jvmTarget.set(JvmTarget.JVM_11)
            // https://youtrack.jetbrains.com/issue/KT-73255/Change-defaulting-rule-for-annotations
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    // hack workaround lint gradle 8.0.2
    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module", // cannot be excluded actually
                    "/META-INF/androidx/**",
                    "/DebugProbesKt.bin",
                    "/kotlin-tooling-metadata.json",
                )
        }
    }
}

kotlin {
    sourceSets.configureEach {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/$name/kotlin"))
    }
}

aboutLibraries {
    collect {
        configPath.set(file("licenses").takeIf { it.exists() })
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.set(
            setOf("generated", "developers", "organization", "scm", "funding", "content"),
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android.applicationVariants.all {
    val variantName = name.replaceFirstChar { it.uppercase() }
    tasks.findByName("generateDataChecksums")?.also {
        tasks.getByName("merge${variantName}Assets").dependsOn(it)
    }
}

dependencies {
    ksp(project(":codegen"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.flexbox)
    implementation(libs.bravh)
    implementation(libs.timber)
    implementation(libs.xxpermissions)
    implementation(libs.kodein.di)
    implementation(libs.snakeyaml)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.iconics.core)
    implementation(libs.community.material.typeface) {
        artifact { type = "aar" }
    }

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
}

// trime-9key: custom Rime data (e.g. rime-ice emoji opencc map) that must land
// in the build-managed, git-ignored assets/shared/opencc directory. Kept under
// the tracked app/rime-data/ and copied in after installOpenCCData (so it isn't
// wiped by cleanOpenCCData) and before generateDataChecksums (so it's bundled).
val copyCustomRimeData by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("rime-data/opencc"))
    into(layout.projectDirectory.dir("src/main/assets/shared/opencc"))
    mustRunAfter("installOpenCCData")
}
tasks.named("generateDataChecksums").configure { dependsOn(copyCustomRimeData) }

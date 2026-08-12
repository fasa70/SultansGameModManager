import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val generatedReleaseAssets = layout.buildDirectory.dir("generated/release-assets")
val releaseTemplate = providers.gradleProperty("releaseTemplate").orNull
val appPackagingRequested = gradle.startParameter.taskNames.any { task ->
    task.contains(":app:") && (
        task.contains("assemble", ignoreCase = true) ||
            task.contains("bundle", ignoreCase = true) ||
            task.contains("package", ignoreCase = true) ||
            task.contains("install", ignoreCase = true) ||
            task.contains("connected", ignoreCase = true)
    )
}
val releaseBuildRequested = gradle.startParameter.taskNames.any { task ->
    task.contains(":app:") && task.contains("release", ignoreCase = true)
}
val releaseTemplateFile = releaseTemplate?.let(::file)

if (appPackagingRequested && releaseTemplateFile?.isFile != true) {
    throw GradleException("app packaging requires -PreleaseTemplate=<loader template APK path>")
}

val syncReleaseTemplate = tasks.register<Sync>("syncReleaseTemplate") {
    releaseTemplateFile?.let { from(it) }
    into(generatedReleaseAssets.map { it.dir("release") })
    rename { "modloader-template-10005.apk" }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncReleaseTemplate)
}

tasks.matching { it.name.contains("lint", ignoreCase = true) && it.name.contains("Release") }.configureEach {
    dependsOn(syncReleaseTemplate)
}

android {
    namespace = "com.sultansgame.modmanager"
    compileSdk = libs.versions.compileSdk.get().toInt()

    val releaseKeystore = rootProject.file("../../release/manager-release.jks")
    val releasePassword = rootProject.file("../../release/manager-release-password.txt")
        .takeIf { it.isFile }?.readText()?.trim()
    if (releaseBuildRequested && (!releaseKeystore.isFile || releasePassword.isNullOrBlank())) {
        throw GradleException(
            "release build requires release/manager-release.jks and " +
                "release/manager-release-password.txt",
        )
    }

    signingConfigs {
        if (releaseKeystore.isFile && !releasePassword.isNullOrBlank()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releasePassword
                keyAlias = "manager-release"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    defaultConfig {
        applicationId = "com.sultansgame.modmanager"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedReleaseAssets.get().asFile)
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":core:merge"))
    implementation(project(":core:workshop"))
    implementation(project(":core:steam-protocol"))
    implementation(project(":core:workshop-download"))
    implementation(project(":core:apk"))
    implementation(project(":core:game-bridge"))
    implementation(project(":core:loader-split"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.apksig)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zip4j)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}

import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
}

val managerCertificateSha256 = providers.gradleProperty("managerCertificateSha256").orNull.orEmpty()
val modloaderBinary = providers.gradleProperty("modloaderBinary").orNull
val releaseBuildRequested = gradle.startParameter.taskNames.any { task ->
    task.contains("release", ignoreCase = true)
}
val generatedAssets = layout.buildDirectory.dir("generated/modloader-assets")

if (releaseBuildRequested && !managerCertificateSha256.matches(Regex("[0-9a-f]{64}"))) {
    throw GradleException("release split requires -PmanagerCertificateSha256=<64 lowercase hex characters>")
}
if (releaseBuildRequested && modloaderBinary.isNullOrBlank()) {
    throw GradleException("release split requires -PmodloaderBinary=<arm64 libmodloader.so path>")
}
if (releaseBuildRequested && !file(requireNotNull(modloaderBinary)).isFile) {
    throw GradleException("modloaderBinary does not exist: $modloaderBinary")
}

val syncModloaderAsset = tasks.register<Sync>("syncModloaderAsset") {
    from(modloaderBinary)
    into(generatedAssets.map { it.dir("modloader/arm64-v8a") })
    rename { "modloader.bin" }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncModloaderAsset)
}

android {
    namespace = "com.gametree.sultan.pd.mod"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        buildConfigField(
            "String",
            "MANAGER_CERTIFICATE_SHA256",
            "\"$managerCertificateSha256\"",
        )
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedAssets.get().asFile)
    }

    buildFeatures {
        buildConfig = true
    }
}

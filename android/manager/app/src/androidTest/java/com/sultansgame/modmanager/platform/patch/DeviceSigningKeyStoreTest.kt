package com.sultansgame.modmanager.platform.patch

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sultansgame.modmanager.model.ApkInspection
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import com.sultansgame.modmanager.split.LoaderSplitRequest
import com.sultansgame.modmanager.split.LoaderSplitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.Signature
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DeviceSigningKeyStoreTest {
    @Test
    fun createsReusableSigningIdentity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DeviceSigningKeyStore(context)
        assertTrue(store.state() != DeviceSigningKeyState.MissingAfterMigration)
        val first = store.getOrCreate()
        val second = store.getOrCreate()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(first.privateKey)
        signature.update("device-signing-smoke".toByteArray())
        val signed = signature.sign()
        signature.initVerify(first.certificateChain.first())
        signature.update("device-signing-smoke".toByteArray())
        assertEquals(DeviceSigningKeyState.Ready, store.state())
        assertEquals(first.certificateSha256, second.certificateSha256)
        assertNotNull(first.certificateChain.first())
        assertTrue(signature.verify(signed))
    }

    @Test
    fun resignsApplicationApkWithV1AndV2Signatures() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val input = File(requireNotNull(context.applicationInfo.sourceDir))
        val output = File(context.cacheDir, "signed-manager-smoke.apk").apply { delete() }
        val result = AndroidKeystoreApkSigner().sign(input, output, DeviceSigningKeyStore(context).getOrCreate())
        assertTrue((result as? ApkSigningResult.Failed)?.reason, result is ApkSigningResult.Signed)
        result as ApkSigningResult.Signed
        assertTrue(result.output.isFile)
        assertTrue(result.verifiedV1)
        assertTrue(result.verifiedV2)
    }

    @Test
    fun resignsBundledLoaderTemplateWithoutInstalledGame() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val identity = DeviceSigningKeyStore(context).getOrCreate()
        val transactionRoot = File(context.filesDir, "patch-staging/${UUID.randomUUID()}").apply { mkdirs() }
        val target = ApkInspection(
            sourceLabel = "installed-base.apk",
            packageName = "com.gametree.sultan.pd",
            versionCode = 10005L,
            versionName = "1.0.5",
            splitName = null,
            supportedAbis = setOf("arm64-v8a"),
            signerDigestsSha256 = emptySet(),
            entryCount = 1,
            sizeBytes = 1L,
            warnings = emptyList(),
        )
        try {
            val loader = AndroidLoaderSplitArtifactFactory(context).build(
                LoaderSplitRequest(
                    targetApplicationId = "com.gametree.sultan.pd",
                    loaderSplitName = "modloader",
                    target = target,
                    templateOutputPath = File(transactionRoot, "template/modloader.apk").absolutePath,
                ),
            )
            assertTrue((loader as? LoaderSplitResult.Unavailable)?.reason, loader is LoaderSplitResult.Built)
            loader as LoaderSplitResult.Built
            val signed = File(context.cacheDir, "signed-loader-${UUID.randomUUID()}.apk")
            try {
                val result = AndroidKeystoreApkSigner().sign(File(loader.artifact.path), signed, identity)
                assertTrue((result as? ApkSigningResult.Failed)?.reason, result is ApkSigningResult.Signed)
                result as ApkSigningResult.Signed
                assertTrue(result.verifiedV1)
                assertTrue(result.verifiedV2)
                val parsed = AndroidApkArchiveInspector(context).inspect(signed, signed.name)
                assertEquals(setOf(identity.certificateSha256), parsed.signerDigestsSha256)
                assertEquals(null, signedArtifactInspectionFailureReason(loader.artifact.inspection, parsed, identity.certificateSha256))
                ZipFile(signed).use { archive ->
                    val entry = requireNotNull(archive.getEntry(NATIVE_ASSET))
                    assertEquals(ZipEntry.STORED, entry.method)
                    assertTrue(entry.size > 0L)
                }
            } finally {
                signed.delete()
            }
        } finally {
            transactionRoot.deleteRecursively()
        }
    }

    @Test
    fun frozenLoaderTemplateExportsUnrestrictedModStorageProvider() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val template = File(context.cacheDir, "modloader-template-manifest.apk").apply {
            context.assets.open("release/modloader-template-10005.apk").use { input -> outputStream().use(input::copyTo) }
        }
        try {
            val packageInfo = requireNotNull(context.packageManager.getPackageArchiveInfo(template.absolutePath, PackageManager.GET_PROVIDERS))
            val provider = requireNotNull(packageInfo.providers?.singleOrNull { it.authority == "com.gametree.sultan.pd.modstorage" })
            assertEquals("com.gametree.sultan.pd", packageInfo.packageName)
            assertEquals("modloader", packageInfo.splitNames.singleOrNull())
            assertTrue(provider.exported)
            assertEquals(":modstorage", provider.processName)
            assertEquals(null, provider.readPermission)
            assertEquals(null, provider.writePermission)
        } finally {
            template.delete()
        }
    }

    @Test
    fun frozenLoaderTemplateDeclaresInvisibleModStorageKickstartActivity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val template = File(context.cacheDir, "modloader-template-kickstart.apk").apply {
            context.assets.open("release/modloader-template-10005.apk").use { input -> outputStream().use(input::copyTo) }
        }
        try {
            val packageInfo = requireNotNull(
                context.packageManager.getPackageArchiveInfo(template.absolutePath, PackageManager.GET_ACTIVITIES),
            )
            val activity = requireNotNull(
                packageInfo.activities?.singleOrNull { it.name == "com.gametree.sultan.pd.mod.ModServiceKickstartActivity" },
            )
            assertTrue(activity.exported)
            assertEquals(":modstorage", activity.processName)
            assertEquals(android.R.style.Theme_NoDisplay, activity.theme)
            assertTrue(activity.excludeFromRecents)
            assertEquals(null, activity.permission)
        } finally {
            template.delete()
        }
    }

    @Test
    fun preparesSignedMigrationArtifactSet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val identity = DeviceSigningKeyStore(context).getOrCreate()
        val extracted = InstalledApkExtractor(context).extract("com.gametree.sultan.pd")
        val outputRoot = File(requireNotNull(context.getExternalFilesDir(null)), "migration-smoke-${UUID.randomUUID()}").apply { mkdirs() }
        val signer = AndroidKeystoreApkSigner()
        val signedBase = File(outputRoot, "base.apk")
        val baseResult = signer.sign(extracted.base.file, signedBase, identity)
        assertTrue((baseResult as? ApkSigningResult.Failed)?.reason, baseResult is ApkSigningResult.Signed)
        baseResult as ApkSigningResult.Signed
        assertTrue(baseResult.verifiedV1)
        assertTrue(baseResult.verifiedV2)
        val loader = AndroidLoaderSplitArtifactFactory(context).build(
            LoaderSplitRequest(
                targetApplicationId = "com.gametree.sultan.pd",
                loaderSplitName = "modloader",
                target = extracted.base.inspection,
                templateOutputPath = File(context.filesDir, "patch-staging/${UUID.randomUUID()}/template/modloader.apk").absolutePath,
            ),
        )
        assertTrue((loader as? LoaderSplitResult.Unavailable)?.reason, loader is LoaderSplitResult.Built)
        loader as LoaderSplitResult.Built
        val signedLoader = File(outputRoot, "modloader.apk")
        val loaderResult = signer.sign(File(loader.artifact.path), signedLoader, identity)
        assertTrue((loaderResult as? ApkSigningResult.Failed)?.reason, loaderResult is ApkSigningResult.Signed)
        loaderResult as ApkSigningResult.Signed
        assertTrue(loaderResult.verifiedV1)
        assertTrue(loaderResult.verifiedV2)
        assertTrue(signedBase.isFile)
        assertTrue(signedLoader.isFile)
    }

    private companion object {
        const val NATIVE_ASSET = "assets/modloader/arm64-v8a/modloader.bin"
    }
}

package io.github.mangi.eta.local.richcontent

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ExternalHtmlPreviewTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uri get() = Uri.parse("content://${externalHtmlAuthority(context)}/html/preview.html")

    @Before fun clearPreviewCache() {
        File(context.cacheDir, "external-html-preview").deleteRecursively()
    }

    private fun addHandler(packageName: String, html: Boolean = true, browser: Boolean = true) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.ViewActivity"
                exported = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
            handleAllWebDataURI = browser
        }
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
                .addCategory(Intent.CATEGORY_BROWSABLE), info,
        )
        if (html) pm.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html"), info)
    }

    @Test fun providerIsPrivateAndExposesOnlyDedicatedCache() {
        val info = context.packageManager.getProviderInfo(
            ComponentName(context, ExternalHtmlPreviewProvider::class.java), PackageManager.GET_META_DATA,
        )
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        val outside = File(context.cacheDir, "private.html").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, externalHtmlAuthority(context), outside)
        }
        val escaped = File(context.cacheDir, "external-html-preview/../private.html")
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, externalHtmlAuthority(context), escaped)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyExternalHtmlPreview(context, File(context.filesDir, "private.html").path)
        }
    }

    @Test fun copyPreservesHtmlBytesAndDoesNotShareAdjacentAssets() {
        val html = "<!doctype html><meta http-equiv='Content-Security-Policy' content=\"default-src 'self'\"><script>window.x=1</script>"
        val original = LocalPreviewFiles.saveCode(context, html)
        val adjacent = File(original.parentFile, "asset.js").apply { writeText("private asset") }
        val shared = copyExternalHtmlPreview(context, original.path)
        assertEquals("content", shared.scheme)
        assertEquals(externalHtmlAuthority(context), shared.authority)
        val provider = Robolectric.buildContentProvider(ExternalHtmlPreviewProvider::class.java)
            .create(externalHtmlAuthority(context)).get()
        provider.openFile(shared, "r").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                assertArrayEquals(original.readBytes(), it.readBytes())
            }
        }
        assertEquals("text/html", provider.getType(shared))
        assertEquals(html, original.readText())
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, externalHtmlAuthority(context), adjacent)
        }
        assertThrows(SecurityException::class.java) {
            provider.openFile(Uri.parse("content://${externalHtmlAuthority(context)}/html/%2E%2E/private.html"), "r")
        }
    }

    @Test fun providerRejectsAllWriteModesAndDelete() {
        val shared = copyExternalHtmlPreview(context, LocalPreviewFiles.saveCode(context, "<p>one</p>").path)
        val provider = Robolectric.buildContentProvider(ExternalHtmlPreviewProvider::class.java)
            .create(externalHtmlAuthority(context)).get()
        listOf("w", "wt", "wa", "rw", "rwt").forEach { mode ->
            assertThrows(FileNotFoundException::class.java) { provider.openFile(shared, mode) }
        }
        assertThrows(SecurityException::class.java) { provider.delete(shared, null, null) }
    }

    @Test fun cacheIsBoundedAndSourceIsUnmodified() {
        val original = LocalPreviewFiles.saveCode(context, "<p>same</p>")
        repeat(12) { copyExternalHtmlPreview(context, original.path) }
        val files = File(context.cacheDir, "external-html-preview").listFiles()!!
        assertEquals(8, files.size)
        assertTrue(files.all { it.extension == "html" })
        assertEquals("<p>same</p>", original.readText())
    }

    @Test fun grantedContentIsCopiedAndOversizedContentIsRejected() {
        val source = Uri.parse("content://granted.test/document/one")
        val bytes = "<p>granted content</p>".toByteArray()
        shadowOf(context.contentResolver).registerInputStream(source, ByteArrayInputStream(bytes))
        copyExternalHtmlPreview(context, source.toString())
        assertArrayEquals(bytes, File(context.cacheDir, "external-html-preview").listFiles()!!.single().readBytes())
        val large = Uri.parse("content://granted.test/document/large")
        shadowOf(context.contentResolver).registerInputStream(
            large, ByteArrayInputStream(ByteArray(LocalPreviewFiles.MAX_DOCUMENT_BYTES + 1)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            copyExternalHtmlPreview(context, large.toString())
        }
    }

    @Test fun defaultBrowserReceivesReadOnlyHtmlIntent() {
        addHandler("com.microsoft.emmx")
        addHandler("org.other.browser")
        val intent = externalHtmlPreviewIntent(context, uri, "com.microsoft.emmx")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.microsoft.emmx", intent.`package`)
        assertEquals(uri, intent.data)
        assertEquals("text/html", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
    }

    @Suppress("DEPRECATION")
    @Test fun defaultBrowserIsResolvedAutomaticallyFromGenericHttps() {
        addHandler("org.other.browser")
        addHandler("com.microsoft.emmx")
        val preferred = ComponentName("com.microsoft.emmx", "com.microsoft.emmx.ViewActivity")
        context.packageManager.addPreferredActivity(
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
            },
            IntentFilter.MATCH_CATEGORY_SCHEME,
            arrayOf(ComponentName("org.other.browser", "org.other.browser.ViewActivity"), preferred),
            preferred,
        )
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
                .addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertEquals("com.microsoft.emmx", resolved!!.activityInfo.packageName)
        val intent = externalHtmlPreviewIntent(context, uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.microsoft.emmx", intent.`package`)
        assertEquals("text/html", intent.type)
        assertEquals(uri, intent.data)
    }

    @Test fun unsupportedDefaultFallsBackToOnlyHtmlCapableBrowser() {
        addHandler("org.default.browser", html = false)
        addHandler("org.html.browser")
        addHandler("org.document.editor", browser = false)
        val intent = externalHtmlPreviewIntent(context, uri, "org.default.browser")
        assertEquals("org.html.browser", intent.`package`)
        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    @Suppress("DEPRECATION")
    @Test fun chooserContainsOnlyHtmlBrowsersNotEditorsOrDeepLinks() {
        addHandler("org.a.browser")
        addHandler("org.b.browser")
        addHandler("org.editor", browser = false)
        addHandler("org.https.only", html = false)
        val chooser = externalHtmlPreviewIntent(context, uri, null)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val first = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val rest = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertEquals(setOf("org.a.browser", "org.b.browser"), (listOf(first) + rest).map { it.`package` }.toSet())
        (listOf(first) + rest).forEach {
            assertEquals(uri, it.data)
            assertEquals("text/html", it.type)
            assertTrue(it.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun noCompatibleBrowserFailsClearlyEvenIfAnEditorExists() {
        addHandler("org.editor", browser = false)
        addHandler("org.browser", html = false)
        val error = assertThrows(IllegalStateException::class.java) {
            externalHtmlPreviewIntent(context, uri, null)
        }
        assertTrue(error.message!!.contains("HTML"))
    }

    @Test fun unrelatedUrisAreNeverGranted() {
        listOf("file:///sdcard/Download/a.html", "content://private/secret").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                externalHtmlPreviewIntent(context, Uri.parse(value), null)
            }
        }
    }
}

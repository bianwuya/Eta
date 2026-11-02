package io.github.mangi.eta.local.incoming

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class IncomingShareTargetTest {
    @Test fun etaIsAvailableForTextImagesDocumentsAndMultipleFiles() {
        val context = RuntimeEnvironment.getApplication()
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("text/plain", "image/png", "application/pdf", "video/mp4")) {
                val intent = Intent(action).setType(mime).setPackage(context.packageName)
                @Suppress("DEPRECATION")
                val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                assertTrue("$action $mime should offer Eta", matches.any {
                    it.activityInfo.name == IncomingShareActivity::class.java.name && it.activityInfo.exported
                })
            }
        }
    }
}

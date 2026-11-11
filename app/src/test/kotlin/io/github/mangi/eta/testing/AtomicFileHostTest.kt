package io.github.mangi.eta.testing

import android.app.Application
import android.util.AtomicFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AtomicFileHostTest {
    @Test fun replacingAndRollingBackMatchAndroidFileSemantics() {
        val file = AtomicFile(File(RuntimeEnvironment.getApplication().filesDir, "atomic-host"))
        fun save(value: String) {
            val output = file.startWrite()
            output.write(value.toByteArray())
            file.finishWrite(output)
        }
        save("first")
        save("replacement")
        assertEquals("replacement", String(file.readFully()))
        val output = file.startWrite()
        output.write("partial".toByteArray())
        file.failWrite(output)
        assertEquals("replacement", String(file.readFully()))
    }
}

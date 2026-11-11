package io.github.mangi.eta.testing

import java.io.File
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** FileProvider's Android boundary uses '/', whereas the host canonical path uses '\\'. */
@Implements(className = "androidx.core.content.FileProvider\$SimplePathStrategy", isInAndroidSdk = false)
class HostFileProviderPathShadow {
    @Implementation
    protected fun belongsToRoot(file: String, root: String): Boolean =
        file.replace(File.separatorChar, '/').startsWith(root.replace(File.separatorChar, '/').trimEnd('/') + "/")
}

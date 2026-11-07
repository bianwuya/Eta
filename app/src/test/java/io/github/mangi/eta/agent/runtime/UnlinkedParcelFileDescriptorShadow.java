package io.github.mangi.eta.agent.runtime;

import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowParcelFileDescriptor;

/** Robolectric 4.17 reopens the original pathname, unlike Android's descriptor dup. */
@Implements(ParcelFileDescriptor.class)
public class UnlinkedParcelFileDescriptorShadow extends ShadowParcelFileDescriptor {
    @Implementation
    protected ParcelFileDescriptor dup() throws IOException {
        return open(new File("/proc/self/fd/" + getFd()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}

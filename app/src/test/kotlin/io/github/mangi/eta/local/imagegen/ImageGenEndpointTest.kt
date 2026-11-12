package io.github.mangi.eta.local.imagegen

import org.junit.Assert.*
import org.junit.Test

class ImageGenEndpointTest {
    @Test fun rootVersionAndCompleteEndpointResolveExactlyOnce() {
        for (value in listOf("https://relay.example", "https://relay.example/", "https://relay.example/v1", "https://relay.example/v1/",
            "https://relay.example/v1/images/generations", "https://relay.example/v1/images/generations/")) {
            assertEquals("https://relay.example/v1/images/generations", ImageGenEndpoint.resolve(value).toString())
        }
        assertEquals("https://relay.example/images/generations", ImageGenEndpoint.resolve("https://relay.example/images/generations").toString())
    }

    @Test fun specialRoutesRequireExplicitOverrideAndDuplicatePathsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ImageGenEndpoint.resolve("https://relay.example/custom") }
        for (value in listOf("https://relay.example/v1/v1/images/generations", "https://relay.example/v1/images/generations/images/generations")) {
            assertThrows(IllegalArgumentException::class.java) { ImageGenEndpoint.resolve(value) }
        }
        assertEquals("https://relay.example/special/image", ImageGenEndpoint.resolve("", "https://relay.example/special/image").toString())
        assertEquals("https://relay.example/prefix/v1/images/generations", ImageGenEndpoint.resolve("https://relay.example/prefix/v1/images/generations").toString())
    }

    @Test fun malformedOrCredentialBearingAddressesCannotBeSaved() {
        for (value in listOf("", "relay.example", "ftp://relay.example", "http://relay.example", "https://name:secret@relay.example",
            "https://relay.example/?key=secret", "https://relay.example/#fragment")) {
            assertThrows(value, IllegalArgumentException::class.java) { ImageGenEndpoint.resolve(value) }
        }
        assertEquals("http://127.0.0.1:8765/v1/images/generations", ImageGenEndpoint.resolve("http://127.0.0.1:8765").toString())
    }
}

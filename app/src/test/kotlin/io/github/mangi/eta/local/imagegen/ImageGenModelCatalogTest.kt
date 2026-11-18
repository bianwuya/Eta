package io.github.mangi.eta.local.imagegen

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageGenModelCatalogTest {
    @Test
    fun generationAndCatalogUseSiblingRoutesWithoutRepeatingV1() {
        listOf("https://images.example", "https://images.example/v1/", "https://images.example/v1/images/generations/")
            .forEach { input ->
                val endpoint = checkNotNull(ImageGenEndpoint.resolve(input))
                assertEquals("https://images.example/v1/images/generations", endpoint.toString())
                assertEquals("https://images.example/v1/models", ImageGenEndpoint.models(endpoint).toString())
            }
        val prefixed = checkNotNull(ImageGenEndpoint.resolve("https://images.example/api/v2/images/generations"))
        assertEquals("https://images.example/api/v2/models", ImageGenEndpoint.models(prefixed).toString())
        assertEquals("http://127.0.0.1:8400/v1/images/generations", ImageGenEndpoint.resolve("http://127.0.0.1:8400").toString())
    }

    @Test
    fun rejectsCredentialsUnsafeTransportAndNonImagesRoutes() {
        listOf(
            "http://images.example", "https://user@images.example/v1", "https://@images.example", "ftp://localhost",
            "https://images.example?token=x", "https://images.example/v1#fragment", "https:////images.example",
            "https://images.example/custom", "https://images.example/v1/v1/images/generations",
            "https://images.example/v1/images/generations/images/generations",
        ).forEach { assertNull(it, ImageGenEndpoint.resolve(it)) }
    }

    @Test
    fun refreshCandidateIsNotTheFixedRefreshRowAndHasAnUnchangedModelId() {
        val models = ImageGenModelCatalog.parse(
            """{"data":[
              {"id":"refresh","name":"Synthetic image model","output_modalities":["image"]},
              {"id":"refresh","outputModalities":["IMAGE"]},
              {"id":"imagegen:fixed:refresh","architecture":{"output_modalities":["image"]}},
              {"id":"gpt-image-test"},{"id":"language-only","input_modalities":["image"],"output_modalities":["text"]}
            ]}""",
        )
        assertEquals(listOf("refresh", IMAGE_GEN_REFRESH_ROW_KEY, "gpt-image-test"), models.map { it.modelId })
        assertNotEquals(IMAGE_GEN_REFRESH_ROW_KEY, imageGenModelRowKey(models.first().modelId))
        assertEquals(models.size, models.map { imageGenModelRowKey(it.modelId) }.distinct().size)
        assertNotEquals(IMAGE_GEN_REFRESH_ROW_KEY, imageGenModelRowKey(IMAGE_GEN_REFRESH_ROW_KEY))
    }

    @Test
    fun catalogUsesOnlyExplicitKeyAndNeverSendsAnImagesPost() = runBlocking {
        val target = checkNotNull(ImageGenEndpoint.resolve("https://images.example/v1"))
        var requests = 0
        val client = imageGenFixtureHttp { request ->
            requests++
            assertEquals("GET", request.method)
            assertEquals("https://images.example/v1/models", request.url.toString())
            assertEquals("Bearer fixture-images-key", request.header("Authorization"))
            assertNull(request.body)
            200 to """{"data":[{"id":"refresh","output_modalities":["image"]}]}"""
        }
        assertEquals(listOf(ImageGenModelCandidate("refresh")), ImageGenModelCatalog.fetch(target, "fixture-images-key", client))
        assertEquals(1, requests)
    }

    @Test
    fun malformedCatalogIsAFailureNotAnEmptySuccess() {
        assertThrows(IllegalArgumentException::class.java) { ImageGenModelCatalog.parse("""{"error":{},"data":[]}""") }
        assertEquals(emptyList<ImageGenModelCandidate>(), ImageGenModelCatalog.parse("""{"data":[]}"""))
    }
}

package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.graphics.Bitmap
import android.os.*
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MultimodalSteeringIpcTest {
    private fun image(): AgentModelClient.ModelImage {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        return try { AgentImageCodec.fromScreenBitmap(bitmap, "fixture") } finally { bitmap.recycle() }
    }
    private fun copy(bundle: Bundle): Bundle {
        val p = Parcel.obtain()
        return try {
            p.writeBundle(bundle)
            // Robolectric permits one functional PFD per parcel family; release sender ownership
            // before reading the duplicated receiver. Android Binder itself dup()s this ownership.
            AgentRuntimeWire.closeImageDescriptors(bundle)
            p.setDataPosition(0)
            p.readBundle(javaClass.classLoader)!!
        } finally { p.recycle() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("asynchronous steering ACK missing", condition())
    }

    @Test fun imageOnlySteeringUsesOriginalFdAndDeduplicatesAckWithoutCancelling() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val service = serviceController.get()
        val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply { isAccessible = true }.get(service) as RuntimeRunRegistry
        val request = AgentRuntimeWire.RunRequest("images-run", "start", AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff("images-run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"))
        val session = AgentRuntimeSession(request.runId)
        val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run
        run.prepared(request)
        val handler = AgentRuntimeService::class.java.declaredClasses.single { it.simpleName == "IncomingHandler" }
            .getDeclaredConstructor(AgentRuntimeService::class.java).apply { isAccessible = true }.newInstance(service) as Handler
        val replies = mutableListOf<Bundle>()
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) { override fun handleMessage(msg: Message) { replies += msg.data } })
        var cancellations = 0
        session.controller.register { cancellations++ }
        val context = RuntimeEnvironment.getApplication()
        val original = image()
        try {
            fun send(id: String, picture: AgentModelClient.ModelImage, expectedReplyCount: Int, uid: Int = Process.myUid()) {
                AgentRuntimeImageTransfer.prepare(context, listOf(picture)).use { prepared ->
                    val outgoing = AgentRuntimeWire.steeringBundle(request.runId, id, "", prepared.images, context.cacheDir)
                    // No inline image bytes in the Binder parcel.
                    val parcel = Parcel.obtain()
                    try { parcel.writeBundle(outgoing); assertTrue(parcel.dataSize() < 16_384) } finally { parcel.recycle() }
                    handler.handleMessage(Message.obtain(null, AgentRuntimeWire.MSG_STEER_RUN).apply { sendingUid = uid; replyTo = receiver; data = copy(outgoing) })
                    if (expectedReplyCount >= 0) await { replies.size == expectedReplyCount }
                    else shadowOf(Looper.getMainLooper()).idle()
                }
            }
            send("queue-img", original, 1)
            send("queue-img", original, 2)
            assertTrue(replies.all { it.getBoolean("accepted") })
            assertEquals(0, cancellations)
            assertSame(run, registry.get(request.runId))
            val input = session.controller.pollSteeringInput()!!
            assertEquals("", input.text)
            assertEquals(original.reference, input.images.single().reference)
            assertNull(session.controller.pollSteeringInput())
            val payload = AgentUiHandoffPayload.from(run.snapshotRequest().handoff!!.payload)
            assertEquals(listOf(original.reference), payload.supplements.single().images)
            assertEquals("", payload.supplements.single().text)
            val invalid = java.io.File(context.cacheDir, "invalid-steer.png").apply { writeText("not an image") }
            try {
                send("invalid", AgentModelClient.ModelImage(invalid.path, "image/png", invalid.length().toInt()), 3)
                assertFalse(replies.last().getBoolean("accepted"))
                assertTrue(replies.last().getBoolean("invalid_input"))
                assertNull(session.controller.pollSteeringInput())
            } finally { invalid.delete() }
            assertNull(session.controller.pollSteeringInputOrSeal())
            send("too-late", original, 4)
            assertFalse(replies.last().getBoolean("accepted"))
            send("foreign", original, -1, Process.myUid() + 1)
            assertEquals(4, replies.size) // Untrusted sender is rejected without a reply.
            assertNull(session.controller.pollSteeringInput())
            assertEquals(0, cancellations)
        } finally { registry.finish(request.runId, session); serviceController.destroy() }
    }

    @Test fun corruptedPictureIsRejectedWithoutEnqueuingTextOnly() {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "bad-image.bin").apply { writeText("not an image") }
        val wire = AgentRuntimeWire.WireImage(fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), mimeType = "image/png", bytes = file.length().toInt())
        try {
            assertThrows(Exception::class.java) { AgentRuntimeImageTransfer.materializeImages(listOf(wire)) }
        } finally { wire.fileDescriptor?.close(); file.delete() }
    }

    @Test fun largeImageEventsAndOutboxHandoffAreFdBackedAndLossless() {
        val context = RuntimeEnvironment.getApplication()
        val big = "data:image/png;base64," + "A".repeat(400_000)
        val event = AgentEvent.UserSupplementReceived(1, "", "queue", listOf(big))
        val outgoing = AgentRuntimeWire.eventToBundle(event, context.cacheDir)
        try {
            val p = Parcel.obtain()
            try { p.writeBundle(outgoing); assertTrue(p.dataSize() < 16_384) } finally { p.recycle() }
            assertEquals(event, AgentRuntimeWire.eventFromBundle(copy(outgoing)))
        } finally { AgentWireText.close(outgoing) }
        val payload = AgentUiHandoffPayload("conversation", supplements = listOf(AgentUiHandoffPayload.Supplement(1, "", 1, "queue", listOf(big))))
        assertEquals(payload, AgentUiHandoffPayload.from(payload.toJson()))
        val completed = AgentRuntimeWire.CompletedRun(AgentRuntimeWire.EntryHandoff("run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, payload.toJson()), AgentRuntimeWire.RunResult("run", false, ""), 1)
        val bundle = AgentRuntimeWire.completedRunsToBundle(listOf(completed), context.cacheDir)
        try {
            val p = Parcel.obtain()
            try { p.writeBundle(bundle); assertTrue(p.dataSize() < 16_384) } finally { p.recycle() }
            assertEquals(listOf(completed), AgentRuntimeWire.completedRunsFromBundle(copy(bundle)))
        } finally { AgentWireText.close(bundle) }
    }
}

package app.p2p

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadFeedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun feedKeepsTheReadingPositionAndOffersJumpToLatest() {
        val records = mutableStateOf((20 downTo 1).map { message(it) })
        var appsOpened = false
        showFeed(records, apps = { appsOpened = true }).use {
            compose.onNodeWithText("Feed").assertIsDisplayed()
            compose.onNodeWithContentDescription("Apps").assertIsDisplayed()
            compose.onNodeWithText("Message 20").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
            compose.onNodeWithText("Message 5").assertIsDisplayed()

            compose.runOnIdle {
                val latest = records.value.first()
                records.value = listOf(latest.copy(payload = latest.data().put("star", true).toString())) + records.value.drop(1)
            }
            compose.onNodeWithText("Message 5").assertIsDisplayed()

            compose.runOnIdle { records.value = listOf(message(21)) + records.value }
            compose.onNodeWithText("Message 5").assertIsDisplayed()
            compose.onNodeWithContentDescription("Scroll to latest message").performClick()
            compose.onNodeWithText("Message 21").assertIsDisplayed()
            compose.onNodeWithContentDescription("Apps").performClick()
            assertEquals(true, appsOpened)
            compose.runOnIdle { records.value = records.value + message(0) }
            compose.onNodeWithText("Message 21").assertIsDisplayed()
        }
    }

    @Test
    fun feedAndMediaKeepSaveActionsWithoutASavedTab() {
        val audio = Record("audio", "file-sent", obj("thread" to "test-thread", "name" to "Recording.wav",
            "mime" to "audio/wav").toString())
        val video = Record("video", "file-sent", obj("thread" to "test-thread", "name" to "Clip.mp4",
            "mime" to "video/mp4").toString())
        val records = mutableStateOf(listOf(video, audio, message(1)))
        val opened = mutableListOf<String>()
        val reacted = mutableListOf<String>()
        showFeed(records, file = { opened.add(it) }, react = { reacted.add(it) }).use {
            compose.onNodeWithContentDescription("Play video").performClick()
            compose.onNodeWithContentDescription("Play audio").performClick()
            assertEquals(listOf(video.key), opened)
            compose.onAllNodesWithContentDescription("React to attachment")[0].performClick()
            assertEquals(listOf(video.key), reacted)
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
            compose.onNodeWithContentDescription("React to message").performClick()
            assertEquals(listOf(video.key, message(1).key), reacted)
            compose.onNodeWithContentDescription("Save message").performClick()
            compose.onNodeWithText("Media").performClick()
            compose.onAllNodesWithContentDescription("Save attachment")[1].performClick()
            compose.onNodeWithContentDescription("Remove attachment from Saved").performClick()
            compose.onNodeWithContentDescription("Remove attachment from Saved").assertDoesNotExist()
            assertEquals(2, compose.onAllNodesWithContentDescription("Save attachment").fetchSemanticsNodes().size)
            assertEquals(listOf(video.key), opened)
            compose.onNodeWithText("Saved").assertDoesNotExist()
            compose.onNodeWithText("Recording.wav").assertIsDisplayed()
            compose.onNodeWithText("Message 1").assertDoesNotExist()
        }
    }

    @Test
    fun attachmentCaptionsKeepTheirTextAndRenderLinkCards() {
        val url = "https://example.org/route"
        val attachment = Record("clip", "file-received", obj(
            "thread" to "test-thread", "author" to "avery", "name" to "Clip.mp4",
            "mime" to "video/mp4", "state" to "Available", "caption" to "Route details $url"
        ).toString())
        showFeed(mutableStateOf(listOf(attachment))).use {
            compose.onNodeWithText("Route details").assertIsDisplayed()
            compose.onNodeWithText("example.org").assertIsDisplayed()
            compose.onNodeWithText(url, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun showsDownloadProgressAndAttachmentReactionsInTheFeed() {
        val attachment = Record("incoming", "file-received", obj("thread" to "test-thread", "author" to "avery",
            "name" to "Video.mp4", "mime" to "video/mp4", "state" to "Downloading", "offset" to 25, "size" to 100).toString())
        val reaction = Record("reaction", "message-reaction", obj("message" to attachment.key, "text" to "👍").toString())
        showFeed(mutableStateOf(listOf(attachment, reaction))).use {
            compose.onNodeWithText("Downloading · 25%").assertIsDisplayed()
            compose.onNodeWithText("👍").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play video").assertDoesNotExist()
        }
    }

    @Test fun rendersReadableBubblesInLightAndDarkAtLargeFont() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val rows = mutableStateOf(listOf(message(2), Record("own", "message", obj("thread" to "test-thread", "author" to "me", "text" to "A message with comfortable spacing.").toString())))
            showFeed(rows, appearance = Appearance(mode = mode, fontSize = 140)).use { scenario ->
                compose.onNodeWithText("Message 2").assertIsDisplayed()
                compose.onNodeWithContentDescription("React to message").let { /* Both bubbles expose the control. */ }
                val output = File("build/ui-checks/bubbles-${mode.name.lowercase()}.png")
                output.parentFile?.mkdirs()
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    output.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                }
            }
        }
    }

    @Test fun mediaIncludesAllAttachmentTypesAndOpensTheExactApp() {
        val names = listOf("Photo.jpg", "Audio.wav", "Video.mp4", "Document.pdf", "Contact.vcf", "Calculator.xdc", "Package.apk")
        val rows = names.mapIndexed { i, name -> Record("item$i", "file-sent", obj("thread" to "test-thread", "name" to name,
            "mime" to listOf("image/jpeg", "audio/wav", "video/mp4", "application/pdf", "text/vcard", "application/octet-stream", "application/vnd.android.package-archive")[i],
            "webxdc" to (i == 5), "nativeVcard" to (i == 4), "size" to ((i + 1) * 1024L)).toString()) }
        var appOpened: String? = null
        val opened = mutableListOf<String>()
        showFeed(mutableStateOf(rows + message(1)), file = { opened.add(it) }, openApp = { appOpened = it.key }).use { scenario ->
            compose.onNodeWithText("Media").performClick()
            compose.onNodeWithText("Gallery").assertDoesNotExist()
            compose.onNodeWithText("Saved").assertDoesNotExist()
            names.forEachIndexed { i, name ->
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(i)
                compose.onNodeWithText(name).assertIsDisplayed().performClick()
                compose.onNodeWithText("${i + 1}.0 KB").assertIsDisplayed()
            }
            assertEquals("item5", appOpened)
            assertEquals(listOf("item0", "item1", "item2", "item3", "item4", "item6"), opened)
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File("build/ui-checks/conversation-media.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    private fun showFeed(
        records: MutableState<List<Record>>,
        file: (String) -> Unit = {},
        react: (String) -> Unit = {},
        appearance: Appearance = Appearance(),
        apps: (() -> Unit)? = null,
        openApp: ((Record) -> Unit)? = null
    ): ActivityScenario<MainActivity> {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val view = mutableStateOf("Feed")
        val mediaDir = ApplicationProvider.getApplicationContext<P2pApplication>().cacheDir
        return ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            activity.setContent {
                P2pTheme(appearance) {
                    Column(Modifier.fillMaxSize()) {
                        ThreadView("test-thread", view.value, records.value, "me", find = "", searchOpen = false,
                            name = { "Avery" }, photo = { "" }, onView = { view.value = it },
                            quote = null, clearQuote = {}, attachments = emptyList(), preparing = false,
                            removeAttachment = {}, trimAttachment = {}, send = { _, _ -> },
                            preview = {}, openLink = {}, attach = {}, messageMenu = {}, react = react,
                            star = { key ->
                                records.value = records.value.map {
                                    if (it.key != key) it else it.copy(payload = it.data().apply {
                                        put("star", !optBoolean("star"))
                                    }.toString())
                                }
                            },
                            galleryFile = { if (it.kind == "file-sent") File(mediaDir, it.data().getString("name")) else null }, file = file, openApps = apps, openWebxdc = openApp)
                    }
                }
            }
        }
    }

    private fun message(number: Int) = Record("message-" + number, "message",
        obj("thread" to "test-thread", "author" to "avery", "text" to "Message " + number).toString())
}

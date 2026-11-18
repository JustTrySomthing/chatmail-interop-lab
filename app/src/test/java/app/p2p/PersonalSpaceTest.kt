package app.p2p

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalSpaceTest {
    private fun app(id: Int, thread: String, original: Int = 0, deleted: Boolean = false) = Record(
        nativeMessageKey(id), "file-sent", obj("nativeMessage" to id, "thread" to thread,
            "webxdc" to true, "nativeOriginalId" to original, "locallyDeleted" to deleted).toString(), id.toLong())

    @Test fun personalDesktopDoesNotImportSharedAppsOrSavedSharedCopies() {
        val self = nativeThreadKey(12)
        val personal = app(20, self)
        val second = app(21, self)
        val rows = listOf(personal, second, app(22, nativeThreadKey(13)), app(23, self, 22), app(24, self, deleted = true))
        assertEquals(listOf(personal, second), personalApps(rows, self))
        assertTrue(personalApps(rows, null).isEmpty())
    }

    @Test fun desktopOrderKeepsSeparateCopiesAndNewAppsWithoutStaleDuplicates() {
        val apps = (20..34).map { app(it, nativeThreadKey(12)) }
        val order = listOf(apps[3].key, "deleted", apps[3].key, apps[0].key)
        val result = orderedPersonalApps(apps, order)
        assertEquals(15, result.size)
        assertEquals(apps[3], result[0])
        assertEquals(apps[0], result[1])
        assertEquals(apps.toSet(), result.toSet())
        val moved = movedApp(result.map { it.key }, result[0].key, 12)
        assertEquals(result[0].key, moved[12])
        assertEquals(result.map { it.key }.toSet(), moved.toSet())
    }

    @Test fun explicitPagesAndOverflowKeepEveryAppExactlyOnce() {
        val apps = (20..34).map { app(it, nativeThreadKey(12)) }.toMutableList()
        apps[0] = apps[0].copy(payload = apps[0].data().put("desktopPage", 2).toString())
        val pages = personalAppPages(apps)
        assertEquals(3, pages.size)
        assertEquals(apps[0], pages[2][0])
        assertEquals(15, pages.flatten().size)
        assertEquals(apps.toSet(), pages.flatten().toSet())
        assertTrue(pages.all { it.size <= 12 })
    }

    @Test fun savedMarkersRecognizeLegacyAndStandardCopies() {
        fun row(data: org.json.JSONObject) = Record("item", "message", data.toString())
        assertTrue(row(obj("star" to true)).isSavedItem())
        assertTrue(row(obj("nativeSavedId" to 25)).isSavedItem())
        assertTrue(row(obj("savedCopyId" to 25)).isSavedItem())
        assertTrue(row(obj("nativeSelfTalk" to true)).isSavedItem())
        assertFalse(row(obj()).isSavedItem())
    }

    @Test fun nativeSaveReferencesSurviveParsing() {
        val raw = obj("kind" to "message", "id" to 30, "chatId" to 12, "fromId" to 20,
            "showPadlock" to true, "viewType" to "Text", "text" to "Saved text",
            "savedMessageId" to 41, "originalMsgId" to 22)
        val message = requireNotNull(readNativeMessage(raw, 12))
        assertEquals(41, message.savedMessageId)
        assertEquals(22, message.originalMsgId)
    }
    @Test fun movingOverflowAppToFullPreviousPageReallyMovesItAndPreservesEveryInstance() {
        val apps = (20..44).map { app(it, nativeThreadKey(12)) }
        val pages = movedPersonalAppPages(apps, emptyList(), apps[12].key, 0)
        assertEquals(apps[12].key, pages[0][0].key)
        assertEquals(apps.size, pages.flatten().size)
        assertEquals(apps.map { it.key }.toSet(), pages.flatten().map { it.key }.toSet())
        assertTrue(pages.all { it.size <= 12 })
        val persisted = pages.flatMapIndexed { page, entries -> entries.map { it.copy(payload = it.data().put("desktopPage", page).toString()) } }
        assertEquals(pages.map { it.map(Record::key) }, personalAppPages(persisted).map { it.map(Record::key) })
    }

    @Test fun hidingIconsDoesNotPullOtherIconsOffAnExplicitPage() {
        val apps = (20..24).map { app(it, nativeThreadKey(12)) }.toMutableList()
        apps[0] = apps[0].copy(payload = apps[0].data().put("desktopPage", 4).toString())
        for (i in 1..4) apps[i] = apps[i].copy(payload = apps[i].data().put("desktopHidden", true).toString())
        assertEquals(apps[0].key, personalAppPages(apps)[4].single().key)
    }

    @Test fun deletedLegacyStarsDoNotMigrateAndSavedNotesHaveHonestRemovalText() {
        val kept = app(20, nativeThreadKey(12)).copy(payload = obj("star" to true).toString())
        val deleted = kept.copy(key = "deleted", payload = kept.data().put("locallyDeleted", true).toString())
        assertEquals(listOf(kept), legacySavedCandidates(listOf(kept, deleted)))
        assertTrue(savedRemovalExplanation(kept).contains("no other copy"))
        assertTrue(savedRemovalExplanation(kept.copy(payload = obj("nativeOriginalId" to 30).toString())).contains("will remain"))
        assertTrue(kept.copy(payload = obj("caption" to "Find this attachment").toString()).searchText().contains("Find this attachment"))
    }

    @Test fun legacyPostSavingIncludesAllMediaAndRejectsIncompletePosts() {
        val post = Record("post", "post", obj("text" to "Photo", "media" to 1).toString())
        val file = Record("photo", "file-received", obj("post" to "post").toString())
        val other = file.copy(key = "other", payload = obj("post" to "unrelated").toString())
        assertEquals(listOf(post, file), legacySavedParts(post, listOf(file, other)))
        assertTrue(runCatching { legacySavedParts(post, listOf(other)) }.isFailure)
        assertEquals(listOf(file), legacySavedParts(file, listOf(post, file)))
    }

}

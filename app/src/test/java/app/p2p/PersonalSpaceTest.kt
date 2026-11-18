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

    @Test fun personalDeletionRejectsChatAppsAndSavedCopiesAndIsolatesStorage() {
        val item = Record(nativeMessageKey(20), "file-sent", obj("nativeMessage" to 20, "webxdc" to true).toString())
        val native = obj("chatId" to 12, "viewType" to "Webxdc")
        assertTrue(canDeletePersonalApp(item, native, 12))
        assertFalse(canDeletePersonalApp(item, native, 13))
        assertFalse(canDeletePersonalApp(item, obj("chatId" to 12, "viewType" to "Webxdc", "originalMsgId" to 25), 12))
        assertFalse(canDeletePersonalApp(item, obj("chatId" to 12, "viewType" to "Text"), 12))
        assertNotEquals(webxdcHost(PRIMARY_PROFILE, "me@example.org", 20), webxdcHost(PRIMARY_PROFILE, "me@example.org", 21))
        assertNotEquals(webxdcHost(PRIMARY_PROFILE, "me@example.org", 20), webxdcHost("other", "me@example.org", 20))
    }

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
        val moved = personalDesktopLayout(apps, obj("order" to org.json.JSONArray(order))).move(result[0].key, 1, 0)
        assertEquals(result[0].key, moved.pages[1][0])
        assertEquals(result.map { it.key }.toSet(), moved.pages.flatten().filter(String::isNotEmpty).toSet())
    }

    @Test fun explicitPagesAndOverflowKeepEveryAppExactlyOnce() {
        val apps = (20..34).map { app(it, nativeThreadKey(12)) }.toMutableList()
        apps[0] = apps[0].copy(payload = apps[0].data().put("desktopPage", 2).toString())
        val pages = personalDesktopLayout(apps, null).pages
        assertEquals(3, pages.size)
        assertEquals(apps[0].key, pages[2][0])
        assertEquals(15, pages.flatten().filter(String::isNotEmpty).size)
        assertEquals(apps.map(Record::key).toSet(), pages.flatten().filter(String::isNotEmpty).toSet())
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
        val moved = personalDesktopLayout(apps, null).move(apps[12].key, 0, 0)
        assertEquals(apps[12].key, moved.pages[0][0])
        assertEquals(apps.size, moved.pages.flatten().filter(String::isNotEmpty).size)
        assertEquals(apps.map(Record::key).toSet(), moved.pages.flatten().filter(String::isNotEmpty).toSet())
        assertTrue(moved.pages.all { it.size <= DesktopPageSize })
        assertEquals(moved, personalDesktopLayout(apps, moved.encode()))
    }

    @Test fun hidingIconsDoesNotPullOtherIconsOffAnExplicitPage() {
        val apps = (20..24).map { app(it, nativeThreadKey(12)) }.toMutableList()
        apps[0] = apps[0].copy(payload = apps[0].data().put("desktopPage", 4).toString())
        for (i in 1..4) apps[i] = apps[i].copy(payload = apps[i].data().put("desktopHidden", true).toString())
        assertEquals(apps[0].key, personalDesktopLayout(apps, null).pages[4].single())
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

    @Test fun foldersPreserveExactCopiesAcrossRenameHideMoveOutAndDeletion() {
        val apps = (20..36).map { app(it, nativeThreadKey(12)) }
        val first = apps[0].key
        val second = apps[1].key
        val third = apps[2].key
        val folder = "desktop-folder:test"
        val grouped = personalDesktopLayout(apps, null).group(first, second, folder)
            .group(third, folder).rename(folder, " Work ")
        assertEquals(DesktopFolder(folder, "Work", listOf(second, first, third)), grouped.folders.single())
        assertEquals(grouped, personalDesktopLayout(apps, grouped.encode()))
        assertFalse(first in grouped.pages.flatten().filter(String::isNotEmpty))
        val hidden = apps.map { if (it.key == first) it.copy(payload = it.data().put("desktopHidden", true).toString()) else it }
        assertEquals(grouped, personalDesktopLayout(hidden, grouped.encode()))
        val extracted = grouped.move(first, 0, 0)
        assertEquals(first, extracted.pages[0][0])
        assertEquals(listOf(second, third), extracted.folders.single().members)
        val dissolved = extracted.dissolve(folder)
        assertTrue(dissolved.folders.isEmpty())
        assertEquals(apps.size, dissolved.pages.flatten().filter(String::isNotEmpty).size)
        assertEquals(apps.map(Record::key).toSet(), dissolved.pages.flatten().filter(String::isNotEmpty).toSet())
    }

    @Test fun vacantSlotsSurviveMoveReloadAndFolderOperations() {
        val apps = (20..22).map { app(it, nativeThreadKey(12)) }
        val moved = personalDesktopLayout(apps, null).move(apps[0].key, 0, 11)
        assertEquals("", moved.pages[0][0])
        assertEquals(apps[1].key, moved.pages[0][1])
        assertEquals(apps[0].key, moved.pages[0][11])
        assertEquals(moved, personalDesktopLayout(apps, moved.encode()))
        val across = moved.move(apps[0].key, 1, 8)
        val back = across.move(apps[0].key, 0, 9)
        assertEquals(apps[0].key, back.pages[0][9])
        assertEquals(apps[1].key, back.pages[0][1])
        assertEquals(back, personalDesktopLayout(apps, back.encode()))
        val grouped = back.group(apps[1].key, apps[2].key, "desktop-folder:holes")
        assertEquals(apps[0].key, grouped.pages[0][9])
        assertEquals(grouped, personalDesktopLayout(apps, grouped.encode()))
        assertEquals(apps[0].key, grouped.dissolve("desktop-folder:holes").pages[0][9])
    }

}

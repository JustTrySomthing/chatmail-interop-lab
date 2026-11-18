package app.p2p

import org.json.JSONArray
import org.json.JSONObject

internal const val DesktopColumns = 3
internal const val DesktopRows = 4
internal const val DesktopPageSize = DesktopColumns * DesktopRows

internal data class DesktopFolder(val id: String, val name: String, val members: List<String> = emptyList())

internal data class PersonalDesktopLayout(val pages: List<List<String>>, val folders: List<DesktopFolder>) {
    fun pageOf(id: String) = pages.indexOfFirst { id in it }

    fun move(id: String, page: Int, index: Int): PersonalDesktopLayout {
        if (pageOf(id) < 0 && folders.none { id in it.members }) return this
        val sourcePage = pageOf(id)
        val sourceSlot = pages.getOrNull(sourcePage)?.indexOf(id) ?: -1
        val result = pages.map { row -> row.map { if (it == id) "" else it }.toMutableList() }.toMutableList()
        val targetPage = page.coerceIn(0, result.size)
        val targetSlot = index.coerceIn(0, DesktopPageSize - 1)
        val displaced = result.getOrNull(targetPage)?.getOrNull(targetSlot).orEmpty()
        if (displaced.isNotEmpty() && sourcePage >= 0) {
            result[sourcePage][sourceSlot] = displaced
            result[targetPage][targetSlot] = ""
        }
        insert(result, id, targetPage, targetSlot)
        return PersonalDesktopLayout(trim(result), folders.map { it.copy(members = it.members - id) })
    }

    fun addFolder(folder: DesktopFolder, page: Int, index: Int): PersonalDesktopLayout {
        if (folders.any { it.id == folder.id }) return this
        val result = pages.map { it.toMutableList() }.toMutableList()
        insert(result, folder.id, page, index)
        return PersonalDesktopLayout(trim(result), folders + folder)
    }

    fun group(app: String, target: String, newFolderId: String? = null): PersonalDesktopLayout {
        if (app == target || folders.any { it.id == app } || pageOf(app) < 0) return this
        val targetPage = pageOf(target)
        if (targetPage < 0) return this
        val existing = folders.firstOrNull { it.id == target }
        if (existing != null) return PersonalDesktopLayout(
            trim(pages.map { row -> row.map { if (it == app) "" else it } }),
            folders.map { if (it.id == target) it.copy(members = it.members + app) else it }
        )
        val id = newFolderId ?: return this
        if (folders.any { it.id == id }) return this
        return PersonalDesktopLayout(
            trim(pages.map { page -> page.map { when (it) { app -> ""; target -> id; else -> it } } }),
            folders + DesktopFolder(id, "Folder", listOf(target, app))
        )
    }

    fun rename(id: String, name: String): PersonalDesktopLayout {
        val trimmed = name.trim()
        if (!validDesktopFolderName(trimmed)) return this
        return copy(folders = folders.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun dissolve(id: String): PersonalDesktopLayout {
        val folder = folders.firstOrNull { it.id == id } ?: return this
        val page = pageOf(id)
        if (page < 0) return this
        val result = pages.map { it.toMutableList() }.toMutableList()
        val slot = result[page].indexOf(id)
        result[page][slot] = ""
        folder.members.forEachIndexed { index, app ->
            if (index == 0) insert(result, app, page, slot) else append(result, app, page)
        }
        return PersonalDesktopLayout(trim(result), folders - folder)
    }

    fun encode(): JSONObject = obj(
        "pages" to JSONArray(pages.map { JSONArray(it) }),
        "folders" to JSONArray(folders.map { obj("id" to it.id, "name" to it.name, "members" to JSONArray(it.members)) })
    )
}

internal fun validDesktopFolderName(name: String) =
    name.trim().length in 1..40 && name.none(Char::isISOControl)

internal fun personalDesktopLayout(apps: List<Record>, saved: JSONObject?): PersonalDesktopLayout {
    val visible = apps.filterNot { it.data().optBoolean("desktopHidden") }
    val visibleKeys = visible.mapTo(mutableSetOf()) { it.key }
    val claimed = mutableSetOf<String>()
    val folderIds = mutableSetOf<String>()
    val sourceFolders = saved?.optJSONArray("folders")
    val folders = List(sourceFolders?.length() ?: 0) { sourceFolders?.optJSONObject(it) }.mapNotNull { entry ->
        val id = entry?.optString("id").orEmpty()
        val name = entry?.optString("name").orEmpty()
        if (!id.startsWith("desktop-folder:") || !validDesktopFolderName(name) || !folderIds.add(id)) {
            return@mapNotNull null
        }
        DesktopFolder(id, name, entry?.strings("members").orEmpty().filter { !it.startsWith("desktop-folder:") && claimed.add(it) })
    }
    val sourcePages = saved?.optJSONArray("pages")
    val pages = mutableListOf(mutableListOf<String>())
    if (sourcePages == null) {
        for (app in orderedPersonalApps(visible, saved?.strings("order").orEmpty())) {
            val preferred = app.data().optInt("desktopPage").coerceIn(0, apps.size)
            append(pages, app.key, preferred)
        }
    } else {
        repeat(sourcePages.length()) { page ->
            val entries = sourcePages.optJSONArray(page) ?: return@repeat
            repeat(entries.length()) { index ->
                val id = entries.optString(index)
                while (pages.size <= page) pages.add(mutableListOf())
                if (index < DesktopPageSize) {
                    val accepted = (id in visibleKeys || id in folderIds) && claimed.add(id)
                    pages[page].add(if (accepted) id else "")
                } else if ((id in visibleKeys || id in folderIds) && claimed.add(id)) append(pages, id, page + 1)
            }
        }
        folders.filterNot { it.id in claimed }.forEach { append(pages, it.id, pages.lastIndex) }
        // New installs fill the first free page slot. Hidden folder members retain membership.
        visible.sortedBy { it.created }.filterNot { it.key in claimed }.forEach { app ->
            val freePage = pages.indexOfFirst { it.size < DesktopPageSize || "" in it }
            append(pages, app.key, if (freePage == -1) pages.size else freePage)
        }
    }
    return PersonalDesktopLayout(trim(pages), folders)
}

// Empty strings are vacant cells; never compact interior holes or empty interior pages.
private fun append(pages: MutableList<MutableList<String>>, id: String, page: Int) {
    var target = page
    while (target < pages.size && pages[target].size >= DesktopPageSize && "" !in pages[target]) target++
    while (pages.size <= target) pages.add(mutableListOf())
    val vacant = pages[target].indexOf("")
    if (vacant >= 0) pages[target][vacant] = id else pages[target].add(id)
}

private fun insert(pages: MutableList<MutableList<String>>, id: String, page: Int, index: Int) {
    val target = page.coerceIn(0, pages.size)
    while (pages.size <= target) pages.add(mutableListOf())
    val slot = index.coerceIn(0, DesktopPageSize - 1)
    while (pages[target].size <= slot) pages[target].add("")
    val displaced = pages[target][slot]
    pages[target][slot] = id
    if (displaced.isNotEmpty() && displaced != id) append(pages, displaced, target)
}

private fun trim(pages: List<List<String>>): List<List<String>> {
    val result = pages.map { it.dropLastWhile(String::isEmpty) }
    return result.take(result.indexOfLast { it.isNotEmpty() }.plus(1).coerceAtLeast(1))
}

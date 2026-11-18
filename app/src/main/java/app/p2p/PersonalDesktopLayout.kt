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
        val result = pages.map { it.filterNot { key -> key == id }.toMutableList() }.toMutableList()
        insert(result, id, page, index)
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
            trim(pages.map { it - app }),
            folders.map { if (it.id == target) it.copy(members = it.members + app) else it }
        )
        val id = newFolderId ?: return this
        if (folders.any { it.id == id }) return this
        return PersonalDesktopLayout(
            trim(pages.map { page -> (page - app).map { if (it == target) id else it } }),
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
        result[page].removeAt(slot)
        folder.members.forEachIndexed { index, app ->
            val destination = slot + index
            insert(result, app, page + destination / DesktopPageSize, destination % DesktopPageSize)
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
                if ((id in visibleKeys || id in folderIds) && claimed.add(id)) append(pages, id, page)
            }
        }
        folders.filterNot { it.id in claimed }.forEach { append(pages, it.id, pages.lastIndex) }
        // New installs fill the first free page slot. Hidden folder members retain membership.
        visible.sortedBy { it.created }.filterNot { it.key in claimed }.forEach { app ->
            val freePage = pages.indexOfFirst { it.size < DesktopPageSize }
            append(pages, app.key, if (freePage == -1) pages.size else freePage)
        }
    }
    return PersonalDesktopLayout(trim(pages), folders)
}

private fun append(pages: MutableList<MutableList<String>>, id: String, page: Int) {
    var target = page
    while (target < pages.size && pages[target].size >= DesktopPageSize) target++
    while (pages.size <= target) pages.add(mutableListOf())
    pages[target].add(id)
}

private fun insert(pages: MutableList<MutableList<String>>, id: String, page: Int, index: Int) {
    var target = page.coerceIn(0, pages.size)
    if (target == pages.size) pages.add(mutableListOf())
    pages[target].add(index.coerceIn(0, pages[target].size), id)
    while (pages[target].size > DesktopPageSize) {
        val overflow = pages[target].removeAt(DesktopPageSize)
        target++
        if (target == pages.size) pages.add(mutableListOf())
        pages[target].add(0, overflow)
    }
}

private fun trim(pages: List<List<String>>): List<List<String>> =
    pages.take(pages.indexOfLast { it.isNotEmpty() }.plus(1).coerceAtLeast(1))

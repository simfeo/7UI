package com.idimus.sevenui

object LayoutOps {

    fun initial(sortedKeys: List<String>, dockKeys: List<String>): LayoutModel = LayoutModel(
        home = listOf(emptyMap()),
        dock = dockKeys.take(Grid.DOCK_SLOTS).withIndex().associate { it.index to it.value },
        drawer = sortedKeys.chunked(Grid.drawerPage).ifEmpty { listOf(emptyList()) },
    )

    /** Drops uninstalled apps everywhere and appends new ones to the end of the drawer. */
    fun reconcile(m: LayoutModel, sortedKeys: List<String>): LayoutModel {
        val alive = sortedKeys.toSet()
        fun keep(k: String) = k in alive || isFolderKey(k) || isWidgetKey(k)
        val folders = m.folders.mapValues { (_, f) -> f.copy(items = f.items.filter { it in alive }) }
        var drawer = m.drawer.map { page -> page.filter(::keep) }
        val known = drawer.flatten().flatMap { k ->
            if (isFolderKey(k)) folders[folderId(k)]?.items.orEmpty() else listOf(k)
        }.toSet()
        for (key in sortedKeys) if (key !in known) drawer = drawerAppend(drawer, key)
        return cleanup(
            LayoutModel(
                home = m.home.map { page -> page.filterValues(::keep) },
                dock = m.dock.filterValues(::keep),
                drawer = drawer,
                folders = folders,
            )
        )
    }

    /**
     * Removes empty Apps pages, dissolves folders left with one app and forgets unreferenced
     * folders. Blank Home pages stay: the user adds them with "+" and deletes them by hand.
     */
    fun cleanup(m: LayoutModel): LayoutModel {
        fun resolve(k: String): String? {
            if (!isFolderKey(k)) return k
            val items = m.folders[folderId(k)]?.items.orEmpty()
            return when (items.size) {
                0 -> null
                1 -> items[0]
                else -> k
            }
        }
        fun cells(c: Map<Int, String>) = c.mapNotNull { (cell, k) -> resolve(k)?.let { cell to it } }.toMap()

        val home = m.home.map(::cells).ifEmpty { listOf(emptyMap()) }
        val dock = cells(m.dock)
        val drawer = m.drawer.map { it.mapNotNull(::resolve) }.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
        val referenced = (home.flatMap { it.values } + dock.values + drawer.flatten())
            .filter(::isFolderKey).map(::folderId).toSet()
        return LayoutModel(home, dock, drawer, m.folders.filterKeys { it in referenced })
    }

    fun rechunkDrawer(m: LayoutModel): LayoutModel =
        m.copy(drawer = m.drawer.flatten().chunked(Grid.drawerPage).ifEmpty { listOf(emptyList()) })

    private fun drawerAppend(drawer: List<List<String>>, key: String): List<List<String>> {
        val last = drawer.lastOrNull()
        return if (last == null || last.size >= Grid.drawerPage) drawer + listOf(listOf(key))
        else drawer.dropLast(1) + listOf(last + key)
    }

    // Empty pages are kept here on purpose: during a drag the preview must not shift page
    // indices under the pager. cleanup() removes them once the drop is committed.
    fun drawerMove(drawer: List<List<String>>, key: String, page: Int, index: Int): List<List<String>> {
        val pages = drawer.map { (it - key).toMutableList() }.toMutableList()
        while (pages.size <= page) pages.add(mutableListOf())
        pages[page].add(index.coerceIn(0, pages[page].size), key)
        var i = page
        while (pages[i].size > Grid.drawerPage) {
            val overflow = pages[i].removeAt(pages[i].lastIndex)
            if (i + 1 == pages.size) pages.add(mutableListOf())
            pages[i + 1].add(0, overflow)
            i++
        }
        return pages
    }

    fun addToHome(m: LayoutModel, key: String, preferredPage: Int = -1): LayoutModel {
        val order = if (preferredPage in m.home.indices) listOf(preferredPage) + m.home.indices.filter { it != preferredPage }
        else m.home.indices.toList()
        for (page in order) {
            val free = findSpace(m.homeOccupancy(page), 1, 1) ?: continue
            return m.put(Slot.Home(page, free), key)
        }
        return m.put(Slot.Home(m.home.size, 0), key)
    }

    /** Places a widget on [preferredPage] if it fits, else the first page with room, else a new page. */
    fun addWidget(m: LayoutModel, preferredPage: Int, spec: WidgetSpec): LayoutModel {
        val key = widgetKey(spec.id, spec.spanX, spec.spanY)
        val order = listOf(preferredPage) + m.home.indices.filter { it != preferredPage }
        for (page in order) {
            if (page !in m.home.indices) continue
            val cell = findSpace(m.homeOccupancy(page), spec.spanX, spec.spanY) ?: continue
            return m.put(Slot.Home(page, cell), key)
        }
        return m.put(Slot.Home(m.home.size, 0), key)
    }

    /** Top-left cell of the first free spanX x spanY area, scanning row by row. */
    fun findSpace(occupied: Map<Int, String>, spanX: Int, spanY: Int): Int? {
        for (r in 0..Grid.HOME_ROWS - spanY) for (c in 0..Grid.HOME_COLS - spanX) {
            val fits = (r until r + spanY).all { rr -> (c until c + spanX).all { cc -> rr * Grid.HOME_COLS + cc !in occupied } }
            if (fits) return r * Grid.HOME_COLS + c
        }
        return null
    }

    /**
     * Takes the dragged key out of where it came from. Home icons are shortcuts, so a drag
     * from the drawer (or a drawer folder) onto home copies, and only a drop back into the
     * drawer actually moves it.
     */
    fun detach(m: LayoutModel, key: String, source: Slot, toDrawer: Boolean): LayoutModel = when (source) {
        is Slot.Home, is Slot.Dock -> m.clear(source)
        is Slot.Drawer -> if (toDrawer) m.copy(drawer = m.drawer.map { it - key }) else m
        is Slot.InFolder ->
            if (toDrawer || !m.isDrawerFolder(source.id)) removeFromFolder(m, source.id, key) else m
        Slot.Search -> m
    }

    private fun removeFromFolder(m: LayoutModel, id: String, key: String): LayoutModel {
        val f = m.folders[id] ?: return m
        return m.copy(folders = m.folders + (id to f.copy(items = f.items - key)))
    }

    /** Pulls an app out of a folder: back to the drawer end for a drawer folder, dropped for a home one. */
    fun takeOutOfFolder(m: LayoutModel, id: String, key: String): LayoutModel {
        val inDrawer = m.isDrawerFolder(id)
        val r = removeFromFolder(m, id, key)
        return if (inDrawer) r.copy(drawer = drawerAppend(r.drawer, key)) else r
    }

    /** Replaces a drawer folder with its apps at the same position. */
    fun ungroup(m: LayoutModel, id: String): LayoutModel {
        val f = m.folders[id] ?: return m
        val fk = FOLDER_PREFIX + id
        val page = m.drawer.indexOfFirst { fk in it }
        if (page < 0) return m.copy(folders = m.folders - id)
        val index = m.drawer[page].indexOf(fk)
        var drawer: List<List<String>> = m.drawer.map { it - fk }
        f.items.forEachIndexed { i, k -> drawer = drawerMove(drawer, k, page, index + i) }
        return m.copy(drawer = drawer, folders = m.folders - id)
    }

    /** Drops [key] onto the occupied [target]: joins its folder or makes a new one from both icons. */
    fun merge(m: LayoutModel, key: String, source: Slot, target: Slot): LayoutModel {
        val occupant = m.keyAt(target) ?: return m
        if (occupant == key || isFolderKey(key) || isWidgetKey(occupant)) return m
        val toDrawer = target is Slot.Drawer
        val r = detach(m, key, source, toDrawer)
        if (isFolderKey(occupant)) {
            val id = folderId(occupant)
            val f = r.folders[id] ?: return m
            if (key in f.items) return r
            return r.copy(folders = r.folders + (id to f.copy(items = f.items + key)))
        }
        val id = System.nanoTime().toString(36)
        val withFolder = r.copy(folders = r.folders + (id to Folder("Folder", listOf(occupant, key))))
        val fk = FOLDER_PREFIX + id
        return if (toDrawer) {
            withFolder.copy(drawer = withFolder.drawer.map { page -> page.map { if (it == occupant) fk else it } })
        } else {
            withFolder.put(target, fk)
        }
    }

    fun dropOnHome(m: LayoutModel, key: String, source: Slot, target: Slot): LayoutModel {
        if (source == target) return m
        // A drawer folder owns its apps; a second reference on home would share them.
        if (isFolderKey(key) && source is Slot.Drawer) return m
        if (m.keyAt(target) != null) return merge(m, key, source, target)
        return detach(m, key, source, toDrawer = false).put(target, key)
    }
}

package com.idimus.sevenui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Apps search: a narrow white-tinted pill floating over the bottom of the grid.
private val pillBg = Color.White.copy(alpha = 0.88f)
private val pillText = Color(0xFF3A3A3C)

@Composable
fun DrawerScreen(state: LauncherState) {
    val d = state.drag
    val draggingFromDrawer = d != null && d.moved && (d.source is Slot.Drawer || d.source is Slot.InFolder)
    val pager = rememberPagerState { state.drawerPageCount() }
    state.drawerPager = pager
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectScreenGestures(state, fromDrawer = true) }
            // Swallows taps on empty space so they do not reach home icons underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                if (draggingFromDrawer) DropZoneBar(stringResource(R.string.add_to_home), active = d?.zone == Zone.ADD_TO_HOME)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { state.drawerGrid = it.boundsInRoot() },
            ) {
                if (state.searching) SearchResults(state) else DrawerPages(state, pager)
            }
            if (!state.searching) {
                PageDots(state.drawerPageCount(), pager.currentPage, Modifier.align(Alignment.CenterHorizontally), onSelect = state::goToDrawerPage)
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (state.searching) SearchField(state) else SearchPill(state)
            }
        }
    }
}

@Composable
private fun SearchPill(state: LauncherState) {
    Row(
        Modifier
            .width(260.dp)
            .height(44.dp)
            .shadow(6.dp, CircleShape)
            .background(pillBg, CircleShape)
            .clickable { state.searching = true }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = pillText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.search), color = pillText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        // Apps screen menu; it opens the same settings as Home.
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            Icon(
                Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options), tint = pillText,
                modifier = Modifier.size(32.dp).clickable { menuOpen = true }.padding(4.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = {
                    menuOpen = false
                    state.settingsOpen = true
                })
            }
        }
    }
}

@Composable
private fun SearchField(state: LauncherState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = state.query,
        onValueChange = { state.query = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            searchApps(state, state.query.trim()).firstOrNull()?.let { state.launch(it, null) }
        }),
        textStyle = TextStyle(color = pillText, fontSize = 16.sp),
        cursorBrush = SolidColor(pillText),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(48.dp)
            .focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .background(pillBg, CircleShape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = pillText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box {
                    if (state.query.isEmpty()) Text(stringResource(R.string.search), color = pillText.copy(alpha = 0.6f), fontSize = 16.sp)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun DrawerPages(state: LauncherState, pager: PagerState) {
    val pages = state.previewDrawer()
    // Touches are handled by detectScreenGestures, which picks one axis per gesture.
    HorizontalPager(state = pager, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) { page ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val ch = maxHeight / Grid.drawerRows
            LazyVerticalGrid(
                columns = GridCells.Fixed(Grid.drawerCols),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(pages.getOrNull(page).orEmpty(), key = { _, k -> k }) { index, k ->
                    ItemCell(
                        state, k, Slot.Drawer(page, index),
                        Modifier.animateItem(placementSpec = tween(230)).height(ch),
                    )
                }
            }
        }
    }
}

/** Labels starting with the query first, then word starts, then any match. */
private fun searchApps(state: LauncherState, q: String): List<AppEntry> {
    if (q.isEmpty()) return emptyList()
    fun rank(label: String) = when {
        label.startsWith(q, ignoreCase = true) -> 0
        label.split(' ', '-', '.').any { it.startsWith(q, ignoreCase = true) } -> 1
        label.contains(q, ignoreCase = true) -> 2
        else -> -1
    }
    return state.sortedApps.map { it to rank(it.label) }.filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
}

@Composable
private fun SearchResults(state: LauncherState) {
    val q = state.query.trim()
    val results = searchApps(state, q)
    // Reversed so the best matches sit right above the search field and keyboard.
    LazyVerticalGrid(columns = GridCells.Fixed(Grid.drawerCols), reverseLayout = true, modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.key }) { entry ->
            ItemCell(state, entry.key, Slot.Search, Modifier.height(96.dp), dragEnabled = false)
        }
    }
}

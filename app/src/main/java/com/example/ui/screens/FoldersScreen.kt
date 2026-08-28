package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SyncFolderConfigEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.*

enum class FolderViewMode {
    TREE,
    FLAT_LIST
}

/**
 * Hierarchical Tree Node representation for multi-level folder structures.
 */
data class TreeNode(
    val id: String,
    val name: String,
    val path: String,
    val depth: Int,
    val folder: SyncFolderConfigEntity?,
    val isSelected: Boolean,
    val isSynthesized: Boolean = false,
    val children: MutableList<TreeNode> = mutableListOf()
) {
    fun isLeaf(): Boolean = children.isEmpty()

    fun selectedDescendantsCount(): Int {
        var count = 0
        for (child in children) {
            if (child.isSelected) count++
            count += child.selectedDescendantsCount()
        }
        return count
    }

    fun totalDescendantsCount(): Int {
        var count = children.size
        for (child in children) {
            count += child.totalDescendantsCount()
        }
        return count
    }

    fun getAllDescendantPaths(): List<String> {
        val list = mutableListOf<String>()
        for (child in children) {
            list.add(child.path)
            list.addAll(child.getAllDescendantPaths())
        }
        return list
    }
}

/**
 * Builds a clean N-level recursive tree from flat folder list, synthesizing any missing intermediate nodes.
 */
private fun buildHierarchyTree(folders: List<SyncFolderConfigEntity>, searchQuery: String): List<TreeNode> {
    if (folders.isEmpty()) return emptyList()

    val folderMap = folders.associateBy { "/" + it.remotePath.trim('/') }
    val nodeMap = mutableMapOf<String, TreeNode>()

    // Identify all unique path segments and ancestors
    val allPaths = mutableSetOf<String>()
    for (folder in folders) {
        val cleanPath = "/" + folder.remotePath.trim('/')
        allPaths.add(cleanPath)

        // Add all intermediate ancestor paths
        val parts = cleanPath.split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            allPaths.add(current)
        }
    }

    // Create TreeNodes for all paths
    for (path in allPaths.sorted()) {
        val entity = folderMap[path]
        val parts = path.split('/').filter { it.isNotEmpty() }
        val depth = (parts.size - 1).coerceAtLeast(0)
        val name = if (parts.isNotEmpty()) parts.last() else "Root"

        val node = TreeNode(
            id = path,
            name = entity?.displayName ?: name,
            path = path,
            depth = depth,
            folder = entity,
            isSelected = entity?.isSelected ?: false,
            isSynthesized = entity == null
        )
        nodeMap[path] = node
    }

    val rootNodes = mutableListOf<TreeNode>()

    // Wire up parent-child relationships
    for (path in allPaths.sorted()) {
        val node = nodeMap[path] ?: continue
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            rootNodes.add(node)
        } else {
            val parentPath = "/" + parts.dropLast(1).joinToString("/")
            val parentNode = nodeMap[parentPath]
            if (parentNode != null && parentNode != node) {
                if (!parentNode.children.contains(node)) {
                    parentNode.children.add(node)
                }
            } else {
                rootNodes.add(node)
            }
        }
    }

    // Apply search filtering if query is present
    if (searchQuery.isNotBlank()) {
        val q = searchQuery.trim().lowercase()
        fun filterTree(nodes: List<TreeNode>): List<TreeNode> {
            val filtered = mutableListOf<TreeNode>()
            for (node in nodes) {
                val matches = node.name.lowercase().contains(q) || node.path.lowercase().contains(q)
                val matchingChildren = filterTree(node.children)
                if (matches || matchingChildren.isNotEmpty()) {
                    val copy = node.copy(children = matchingChildren.toMutableList())
                    filtered.add(copy)
                }
            }
            return filtered
        }
        return filterTree(rootNodes)
    }

    return rootNodes
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    viewModel: MainViewModel
) {
    val folders by viewModel.folders.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val syncNewByDefault = settings?.syncNewFoldersByDefault ?: true

    var viewMode by remember { mutableStateOf(FolderViewMode.TREE) }
    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var showAddFolderDialog by remember { mutableStateOf(false) }
    var addFolderParentPath by remember { mutableStateOf<String?>(null) }
    var selectedNodeForMenu by remember { mutableStateOf<TreeNode?>(null) }

    val treeNodes = remember(folders, searchQuery) {
        buildHierarchyTree(folders, searchQuery)
    }

    // Helper to expand/collapse all
    fun setAllExpanded(expand: Boolean) {
        fun visit(nodes: List<TreeNode>) {
            for (node in nodes) {
                expandedPaths[node.path] = expand
                visit(node.children)
            }
        }
        visit(treeNodes)
    }

    // Default top-level roots to expanded initially
    LaunchedEffect(folders.size) {
        for (f in folders) {
            val clean = "/" + f.remotePath.trim('/')
            if (!clean.drop(1).contains('/')) {
                if (!expandedPaths.containsKey(clean)) {
                    expandedPaths[clean] = true
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    addFolderParentPath = null
                    showAddFolderDialog = true
                },
                containerColor = NcPrimaryBlue,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("add_folder_fab")
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Add folder")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            // 1. Key Setting Card: "Sync new remote folders by default"
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sync_new_folders_setting_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(NcPrimaryBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudSync,
                                    contentDescription = "Sync new folders",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Sync New Nextcloud Folders",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (syncNewByDefault) {
                                        "New folders added to the server are synchronized automatically."
                                    } else {
                                        "New folders on the server are excluded by default until manually selected."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = syncNewByDefault,
                            onCheckedChange = { viewModel.setSyncNewFoldersByDefault(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NcPrimaryBlue
                            ),
                            modifier = Modifier.testTag("sync_new_folders_toggle")
                        )
                    }
                }
            }

            // 2. Header, View Mode Switcher (Tree vs Flat) & Search
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Selective Folder Sync",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Select directories or specific subdirectories to sync independently",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { isSearchActive = !isSearchActive },
                                modifier = Modifier.testTag("toggle_search_btn")
                            ) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Filled.SearchOff else Icons.Filled.Search,
                                    contentDescription = "Search folders",
                                    tint = NcPrimaryBlue
                                )
                            }

                            IconButton(
                                onClick = { viewModel.refreshRemoteFolders() },
                                modifier = Modifier.testTag("refresh_folders_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Scan server folders",
                                    tint = NcPrimaryBlue
                                )
                            }
                        }
                    }

                    // Search input if active
                    AnimatedVisibility(visible = isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter folders by name or path...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("folder_search_input")
                        )
                    }

                    // View Mode Switcher Tabs
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().testTag("folder_view_mode_selector")
                    ) {
                        SegmentedButton(
                            selected = viewMode == FolderViewMode.TREE,
                            onClick = { viewMode = FolderViewMode.TREE },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = {
                                Icon(
                                    Icons.Filled.AccountTree,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Tree View", fontWeight = FontWeight.Medium) }
                        )
                        SegmentedButton(
                            selected = viewMode == FolderViewMode.FLAT_LIST,
                            onClick = { viewMode = FolderViewMode.FLAT_LIST },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = {
                                Icon(
                                    Icons.Filled.ViewList,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Flat List", fontWeight = FontWeight.Medium) }
                        )
                    }

                    // Tree Toolbar: Expand All / Collapse All / Select All / None
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (viewMode == FolderViewMode.TREE) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { setAllExpanded(true) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("expand_all_btn")
                                ) {
                                    Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Expand All", style = MaterialTheme.typography.labelMedium)
                                }

                                TextButton(
                                    onClick = { setAllExpanded(false) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("collapse_all_btn")
                                ) {
                                    Icon(Icons.Filled.UnfoldLess, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Collapse All", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = {
                                    folders.forEach { folder ->
                                        if (!folder.isSelected) {
                                            viewModel.toggleFolderSelection(folder.remotePath, true)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("select_all_folders_btn")
                            ) {
                                Text("All", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            }

                            OutlinedButton(
                                onClick = {
                                    folders.forEach { folder ->
                                        if (folder.isSelected) {
                                            viewModel.toggleFolderSelection(folder.remotePath, false)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("deselect_all_folders_btn")
                            ) {
                                Text("None", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // 3. Folders List / Tree View Content
            if (folders.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "No folders",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Sync Folders Configured",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap 'Scan Server Folders' or tap the '+' button below to discover and configure folders from your Nextcloud server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            } else if (viewMode == FolderViewMode.FLAT_LIST) {
                val filteredFolders = if (searchQuery.isBlank()) folders else folders.filter {
                    it.displayName.contains(searchQuery, ignoreCase = true) || it.remotePath.contains(searchQuery, ignoreCase = true)
                }

                items(filteredFolders, key = { it.remotePath }) { folder ->
                    FolderSyncItemCard(
                        folder = folder,
                        onToggleSelection = { isSelected ->
                            viewModel.toggleFolderSelection(folder.remotePath, isSelected)
                        },
                        onAddSubfolder = {
                            addFolderParentPath = folder.remotePath
                            showAddFolderDialog = true
                        }
                    )
                }
            } else {
                // HIERARCHICAL TREE VIEW (AmrDeveloper/treeview style)
                fun addTreeItems(
                    nodes: List<TreeNode>,
                    targetList: MutableList<TreeNode>
                ) {
                    for (node in nodes) {
                        targetList.add(node)
                        val isExpanded = expandedPaths[node.path] ?: false
                        if (isExpanded && node.children.isNotEmpty()) {
                            addTreeItems(node.children, targetList)
                        }
                    }
                }

                val flattenedTree = mutableListOf<TreeNode>()
                addTreeItems(treeNodes, flattenedTree)

                items(flattenedTree, key = { it.id }) { node ->
                    val isExpanded = expandedPaths[node.path] ?: false
                    TreeViewNodeRow(
                        node = node,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedPaths[node.path] = !isExpanded
                        },
                        onToggleSelection = { isSelected ->
                            viewModel.toggleFolderSelection(node.path, isSelected)
                        },
                        onAddSubfolder = {
                            addFolderParentPath = node.path
                            showAddFolderDialog = true
                        },
                        onSelectSubtree = { select ->
                            viewModel.toggleFolderSelection(node.path, select)
                            for (descPath in node.getAllDescendantPaths()) {
                                viewModel.toggleFolderSelection(descPath, select)
                            }
                        }
                    )
                }
            }

            // 4. Desktop Client Selective Sync Parity Info
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Info",
                                tint = NcPrimaryBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Nextcloud Desktop Selective Sync Parity",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Just like Nextcloud Desktop Client, you can synchronize specific subdirectories deep inside parent folders without synchronizing the entire parent folder. Unselected directories remain safely on your Nextcloud cloud server without consuming local device storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAddFolderDialog) {
        AddFolderDialog(
            presetParentPath = addFolderParentPath,
            onDismiss = { showAddFolderDialog = false },
            onAdd = { fullPath, isSelected ->
                val cleanPath = "/" + fullPath.trim('/')
                viewModel.createRemoteDemoFolder(cleanPath.removePrefix("/"))
                viewModel.createLocalFolder(cleanPath.removePrefix("/"))
                viewModel.toggleFolderSelection(cleanPath, isSelected)
                showAddFolderDialog = false
            }
        )
    }
}

/**
 * Tree View Node Row with indentation guides, expand/collapse chevron, folder icon,
 * independent selection checkbox, selective descendant indicators, and quick subtree actions.
 */
@Composable
private fun TreeViewNodeRow(
    node: TreeNode,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelection: (Boolean) -> Unit,
    onAddSubfolder: () -> Unit,
    onSelectSubtree: (Boolean) -> Unit
) {
    val depth = node.depth
    val hasChildren = !node.isLeaf()
    val selectedDescendants = remember(node) { node.selectedDescendantsCount() }
    val totalDescendants = remember(node) { node.totalDescendantsCount() }
    val isPartiallySelected = !node.isSelected && selectedDescendants > 0

    var showMenu by remember { mutableStateOf(false) }

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "chevronRotation"
    )

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                node.isSelected -> NcPrimaryBlue.copy(alpha = 0.08f)
                isPartiallySelected -> NcCyanAccent.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                node.isSelected -> NcPrimaryBlue.copy(alpha = 0.35f)
                isPartiallySelected -> NcCyanAccent.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (node.isSelected || isPartiallySelected) 1.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 20).dp)
            .testTag("tree_node_${node.path}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Expand / Collapse Chevron Button (or space if leaf)
                if (hasChildren) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(32.dp).testTag("tree_expand_btn_${node.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = NcPrimaryBlue,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotationAngle)
                        )
                    }
                } else {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }

                // 2. Folder Icon (Open vs Closed)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                node.isSelected -> NcPrimaryBlue.copy(alpha = 0.18f)
                                isPartiallySelected -> NcCyanAccent.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable {
                            if (hasChildren) onToggleExpand() else onToggleSelection(!node.isSelected)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            hasChildren && isExpanded -> Icons.Filled.FolderOpen
                            node.isSelected -> Icons.Filled.Folder
                            isPartiallySelected -> Icons.Filled.FolderSpecial
                            else -> Icons.Outlined.Folder
                        },
                        contentDescription = node.name,
                        tint = when {
                            node.isSelected -> NcPrimaryBlue
                            isPartiallySelected -> NcCyanAccent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 3. Folder Name, Path & Selective Sync Badge
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleSelection(!node.isSelected) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (node.isSelected || isPartiallySelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // If has children, show child count badge
                        if (hasChildren) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$totalDescendants",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(1.dp))

                    Text(
                        text = node.path,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Selective Sync Status Indicators
                    if (isPartiallySelected) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircleOutline,
                                contentDescription = null,
                                tint = NcCyanAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "$selectedDescendants subfolder${if (selectedDescendants > 1) "s" else ""} synced (parent excluded)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = NcCyanAccent
                            )
                        }
                    } else if (node.isSelected && selectedDescendants > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Folder + $selectedDescendants subfolders synced",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = NcPrimaryBlue
                        )
                    }
                }

                // 4. More Options (Subtree actions, add subfolder)
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp).testTag("folder_menu_btn_${node.name}")
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Folder options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add Subfolder Here") },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = NcPrimaryBlue) },
                            onClick = {
                                showMenu = false
                                onAddSubfolder()
                            }
                        )
                        if (hasChildren) {
                            DropdownMenuItem(
                                text = { Text("Select Folder & All Subfolders") },
                                leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null, tint = NcPrimaryBlue) },
                                onClick = {
                                    showMenu = false
                                    onSelectSubtree(true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Deselect Subtree") },
                                leadingIcon = { Icon(Icons.Filled.Deselect, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onSelectSubtree(false)
                                }
                            )
                        }
                    }
                }

                // 5. Individual Folder Selection Checkbox
                Checkbox(
                    checked = node.isSelected,
                    onCheckedChange = { onToggleSelection(it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = NcPrimaryBlue
                    ),
                    modifier = Modifier.testTag("checkbox_${node.name}")
                )
            }
        }
    }
}

@Composable
private fun FolderSyncItemCard(
    folder: SyncFolderConfigEntity,
    onToggleSelection: (Boolean) -> Unit,
    onAddSubfolder: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (folder.isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (folder.isSelected) 1.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection(!folder.isSelected) }
            .testTag("folder_item_${folder.displayName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (folder.isSelected) NcPrimaryBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (folder.isSelected) Icons.Filled.Folder else Icons.Outlined.FolderOff,
                        contentDescription = folder.displayName,
                        tint = if (folder.isSelected) NcPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = folder.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (folder.isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Path: ${folder.remotePath} • ${if (folder.isSelected) "Synchronized" else "Excluded"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onAddSubfolder,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = "Add subfolder",
                        tint = NcPrimaryBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Checkbox(
                    checked = folder.isSelected,
                    onCheckedChange = { onToggleSelection(it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = NcPrimaryBlue
                    ),
                    modifier = Modifier.testTag("checkbox_${folder.displayName}")
                )
            }
        }
    }
}

@Composable
private fun AddFolderDialog(
    presetParentPath: String? = null,
    onDismiss: () -> Unit,
    onAdd: (path: String, isSelected: Boolean) -> Unit
) {
    var parentPathInput by remember { mutableStateOf(presetParentPath ?: "/") }
    var folderName by remember { mutableStateOf("") }
    var syncImmediately by remember { mutableStateOf(true) }

    val fullPathPreview = remember(parentPathInput, folderName) {
        val base = "/" + parentPathInput.trim('/')
        if (base == "/") {
            "/$folderName".trimEnd('/')
        } else {
            "$base/${folderName.trim('/')}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = NcPrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (presetParentPath != null) "Add Subfolder" else "Add Sync Folder",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter folder details on Nextcloud to synchronize:",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = parentPathInput,
                    onValueChange = { parentPathInput = it },
                    label = { Text("Parent Directory Path") },
                    placeholder = { Text("/ or /Documents/Work") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("parent_path_input")
                )

                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder / Subfolder Name") },
                    placeholder = { Text("e.g. Reports, Music, 2026_Q3") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_folder_input")
                )

                if (folderName.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Full Target Path:", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = fullPathPreview,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = NcPrimaryBlue
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { syncImmediately = !syncImmediately }
                ) {
                    Checkbox(
                        checked = syncImmediately,
                        onCheckedChange = { syncImmediately = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select for synchronization immediately", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onAdd(fullPathPreview, syncImmediately)
                    }
                },
                enabled = folderName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                modifier = Modifier.testTag("confirm_add_folder_btn")
            ) {
                Text("Create & Configure")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

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
import com.example.ui.FolderRefreshStatus
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
    val isInheritedFromParent: Boolean = false,
    val parentSelectedName: String? = null,
    val isSynthesized: Boolean = false,
    val children: MutableList<TreeNode> = mutableListOf()
) {
    fun isLeaf(): Boolean = children.isEmpty()

    val isEffectivelySelected: Boolean
        get() = isSelected || isInheritedFromParent

    fun selectedDescendantsCount(): Int {
        var count = 0
        for (child in children) {
            if (child.isEffectivelySelected) count++
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
 * Checks if any ancestor of [path] is currently selected in [folders].
 */
fun findSelectedAncestor(path: String, folders: List<SyncFolderConfigEntity>): SyncFolderConfigEntity? {
    val cleanPath = "/" + path.trim('/')
    val parts = cleanPath.split('/').filter { it.isNotEmpty() }
    if (parts.size <= 1) return null

    val folderMap = folders.associateBy { "/" + it.remotePath.trim('/') }
    var current = ""
    for (i in 0 until parts.size - 1) {
        current += "/${parts[i]}"
        val ancestor = folderMap[current]
        if (ancestor != null && ancestor.isSelected) {
            return ancestor
        }
    }
    return null
}

/**
 * Builds a clean N-level recursive tree from flat folder list, synthesizing any missing intermediate nodes
 * and calculating parent-child sync inheritance.
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

        val ancestor = findSelectedAncestor(path, folders)
        val isInherited = ancestor != null
        val parentName = ancestor?.displayName ?: ancestor?.remotePath

        val node = TreeNode(
            id = path,
            name = entity?.displayName ?: name,
            path = path,
            depth = depth,
            folder = entity,
            isSelected = entity?.isSelected ?: false,
            isInheritedFromParent = isInherited,
            parentSelectedName = parentName,
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

data class UnselectWarningData(
    val path: String,
    val displayName: String
)

data class ParentLockedInfoData(
    val subfolderName: String,
    val parentName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    viewModel: MainViewModel
) {
    val folders by viewModel.folders.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val refreshState by viewModel.folderRefreshState.collectAsState()
    val loadingFolderPaths by viewModel.loadingFolderPaths.collectAsState()
    val syncNewByDefault = settings?.syncNewFoldersByDefault ?: true
    val isLazyMode = settings?.lazyLoadSubfolders ?: true

    val canModifyFolders = refreshState.initialLoadCompleted && !refreshState.isRefreshing

    var viewMode by remember { mutableStateOf(FolderViewMode.TREE) }
    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var showAddFolderDialog by remember { mutableStateOf(false) }
    var addFolderParentPath by remember { mutableStateOf<String?>(null) }

    // Dialog States
    var unselectWarning by remember { mutableStateOf<UnselectWarningData?>(null) }
    var parentLockedInfo by remember { mutableStateOf<ParentLockedInfoData?>(null) }
    var showDeselectAllWarning by remember { mutableStateOf(false) }

    val treeNodes = remember(folders, searchQuery) {
        buildHierarchyTree(folders, searchQuery)
    }

    // Helper to expand/collapse all
    fun setAllExpanded(expand: Boolean) {
        fun visit(nodes: List<TreeNode>) {
            for (node in nodes) {
                expandedPaths[node.path] = expand
                if (expand && isLazyMode) {
                    viewModel.fetchSubfoldersForPath(node.path)
                }
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

    // Handler for toggling selection safely with hierarchical constraints and warning
    fun handleFolderToggle(path: String, displayName: String, targetSelected: Boolean, isInherited: Boolean, parentName: String?) {
        if (!canModifyFolders) {
            // Blocked while folder list is downloading or not yet loaded from server
            return
        }

        if (targetSelected) {
            // Selecting is always allowed directly: selects this folder and all its subfolders
            viewModel.toggleFolderSelection(path, true)
        } else {
            // User wants to UNSELECT
            if (isInherited) {
                // Blocked: Cannot unselect subfolder while parent is selected
                parentLockedInfo = ParentLockedInfoData(
                    subfolderName = displayName,
                    parentName = parentName ?: "parent folder"
                )
            } else {
                // Show warning dialog about local deletion
                unselectWarning = UnselectWarningData(
                    path = path,
                    displayName = displayName
                )
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

            // 2. Header, View Mode Switcher (Tree vs Flat), Refresh Status & Search
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
                                text = "Synchronize entire directory trees or selective subdirectories",
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
                                onClick = {
                                    if (!refreshState.isRefreshing) {
                                        viewModel.refreshRemoteFolders()
                                    }
                                },
                                enabled = !refreshState.isRefreshing,
                                modifier = Modifier.testTag("refresh_folders_btn")
                            ) {
                                if (refreshState.isRefreshing) {
                                    CircularProgressIndicator(
                                        color = NcPrimaryBlue,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Scan server folders",
                                        tint = NcPrimaryBlue
                                    )
                                }
                            }
                        }
                    }

                    // Refresh Status Feedback Banner (Active, Success, or Error)
                    if (refreshState.isRefreshing) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NcPrimaryBlue.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NcPrimaryBlue.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().testTag("refresh_status_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = NcPrimaryBlue,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Downloading folders from server...",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = NcPrimaryBlue
                                    )
                                    Text(
                                        text = "Folder selection is temporarily disabled during scan",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NcPrimaryBlue.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    } else if (refreshState.status == FolderRefreshStatus.SUCCESS) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NcSuccessGreen.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NcSuccessGreen.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().testTag("refresh_status_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = NcSuccessGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = refreshState.message ?: "Folder list synchronized with server",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = NcSuccessGreen
                                )
                            }
                        }
                    } else if (refreshState.status == FolderRefreshStatus.ERROR) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().testTag("refresh_status_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = refreshState.message ?: "Failed to refresh folder list",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.refreshRemoteFolders() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Retry", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
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
                                    if (canModifyFolders) {
                                        folders.forEach { folder ->
                                            if (!folder.isSelected) {
                                                viewModel.toggleFolderSelection(folder.remotePath, true)
                                            }
                                        }
                                    }
                                },
                                enabled = canModifyFolders && folders.isNotEmpty(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("select_all_folders_btn")
                            ) {
                                Text("All", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (canModifyFolders) {
                                        val selectedCount = folders.count { it.isSelected }
                                        if (selectedCount > 0) {
                                            showDeselectAllWarning = true
                                        }
                                    }
                                },
                                enabled = canModifyFolders && folders.isNotEmpty(),
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
                            if (refreshState.isRefreshing) {
                                CircularProgressIndicator(
                                    color = NcPrimaryBlue,
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Downloading Folders from Server...",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Connecting to Nextcloud and discovering directory structure. Folder selection will be ready in a moment.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            } else {
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
                                    text = "Tap 'Scan server folders' or tap the '+' button to discover and configure folders from your Nextcloud server.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            } else if (viewMode == FolderViewMode.FLAT_LIST) {
                val filteredFolders = if (searchQuery.isBlank()) folders else folders.filter {
                    it.displayName.contains(searchQuery, ignoreCase = true) || it.remotePath.contains(searchQuery, ignoreCase = true)
                }

                items(filteredFolders, key = { it.remotePath }) { folder ->
                    val ancestor = findSelectedAncestor(folder.remotePath, folders)
                    val isInherited = ancestor != null
                    val parentName = ancestor?.displayName ?: ancestor?.remotePath

                    FolderSyncItemCard(
                        folder = folder,
                        isInheritedFromParent = isInherited,
                        parentSelectedName = parentName,
                        canModify = canModifyFolders,
                        onToggleSelection = { isSelected ->
                            handleFolderToggle(
                                path = folder.remotePath,
                                displayName = folder.displayName,
                                targetSelected = isSelected,
                                isInherited = isInherited,
                                parentName = parentName
                            )
                        },
                        onAddSubfolder = {
                            addFolderParentPath = folder.remotePath
                            showAddFolderDialog = true
                        }
                    )
                }
            } else {
                // HIERARCHICAL TREE VIEW (Multi-level Treeview)
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
                    val isLoadingSubfolders = loadingFolderPaths.contains(node.path)

                    TreeViewNodeRow(
                        node = node,
                        isExpanded = isExpanded,
                        isLoadingSubfolders = isLoadingSubfolders,
                        canModify = canModifyFolders,
                        onToggleExpand = {
                            val nextExpanded = !isExpanded
                            expandedPaths[node.path] = nextExpanded
                            if (nextExpanded && isLazyMode) {
                                viewModel.fetchSubfoldersForPath(node.path)
                            }
                        },
                        onToggleSelection = { isSelected ->
                            handleFolderToggle(
                                path = node.path,
                                displayName = node.name,
                                targetSelected = isSelected,
                                isInherited = node.isInheritedFromParent,
                                parentName = node.parentSelectedName
                            )
                        },
                        onAddSubfolder = {
                            addFolderParentPath = node.path
                            showAddFolderDialog = true
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
                            text = "• Selecting a parent folder automatically synchronizes all its subfolders.\n• Subfolders are locked in sync while the parent is selected.\n• Unselecting a parent folder removes local files (freeing space) and unselects all its subfolders.\n• Once the parent is unselected, you can selectively re-select any individual subfolders you need.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Unselect Warning Dialog
    unselectWarning?.let { warningData ->
        AlertDialog(
            onDismissRequest = { unselectWarning = null },
            icon = {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Unselect Folder & Delete Local Copy?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Unselecting \"${warningData.displayName}\" and all its subfolders from synchronization will delete the local files from this device to free up storage.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.CloudDone,
                                contentDescription = null,
                                tint = NcPrimaryBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "All remote files remain safely preserved on your Nextcloud server.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = warningData.path
                        unselectWarning = null
                        viewModel.toggleFolderSelection(path, false)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_unselect_folder_btn")
                ) {
                    Text("Proceed (Delete Locally)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { unselectWarning = null },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("cancel_unselect_folder_btn")
                ) {
                    Text("Cancel (Keep Synced)")
                }
            }
        )
    }

    // Parent Folder Locked Info Dialog
    parentLockedInfo?.let { infoData ->
        AlertDialog(
            onDismissRequest = { parentLockedInfo = null },
            icon = {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Parent folder selected",
                    tint = NcPrimaryBlue,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Subfolder Included in Sync",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "The subfolder \"${infoData.subfolderName}\" is automatically synchronized because its parent folder \"${infoData.parentName}\" is selected.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "To unselect this subfolder, first unselect the parent folder. You will then be able to selectively re-select any specific subfolders you want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { parentLockedInfo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = NcPrimaryBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Understood")
                }
            }
        )
    }

    // Deselect All Warning Dialog
    if (showDeselectAllWarning) {
        AlertDialog(
            onDismissRequest = { showDeselectAllWarning = false },
            icon = {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Unselect All Folders?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will unselect all folders from synchronization and delete their local copies from this device.\n\nAll files will remain safely stored on your Nextcloud server.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeselectAllWarning = false
                        folders.forEach { folder ->
                            if (folder.isSelected) {
                                viewModel.toggleFolderSelection(folder.remotePath, false)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Proceed (Unselect All)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeselectAllWarning = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
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
 * parent sync lock indicator, selective descendant indicators, loading spinner for subfolders,
 * and add subfolder action.
 */
@Composable
private fun TreeViewNodeRow(
    node: TreeNode,
    isExpanded: Boolean,
    isLoadingSubfolders: Boolean = false,
    canModify: Boolean = true,
    onToggleExpand: () -> Unit,
    onToggleSelection: (Boolean) -> Unit,
    onAddSubfolder: () -> Unit
) {
    val depth = node.depth
    val hasChildren = !node.isLeaf()
    val isInherited = node.isInheritedFromParent
    val isEffectivelySelected = node.isEffectivelySelected
    val selectedDescendants = remember(node) { node.selectedDescendantsCount() }
    val totalDescendants = remember(node) { node.totalDescendantsCount() }
    val isPartiallySelected = !isEffectivelySelected && selectedDescendants > 0

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "chevronRotation"
    )

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isInherited -> NcPrimaryBlue.copy(alpha = 0.05f)
                node.isSelected -> NcPrimaryBlue.copy(alpha = 0.09f)
                isPartiallySelected -> NcCyanAccent.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                node.isSelected -> NcPrimaryBlue.copy(alpha = 0.35f)
                isInherited -> NcPrimaryBlue.copy(alpha = 0.2f)
                isPartiallySelected -> NcCyanAccent.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEffectivelySelected || isPartiallySelected) 1.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).dp)
            .testTag("tree_node_${node.path}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Expand / Collapse Chevron Button (or loading spinner / dot if leaf)
                if (isLoadingSubfolders) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = NcPrimaryBlue,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else if (hasChildren || depth == 0) {
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

                // 2. Folder Icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isEffectivelySelected -> NcPrimaryBlue.copy(alpha = 0.18f)
                                isPartiallySelected -> NcCyanAccent.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable {
                            if (hasChildren || depth == 0) onToggleExpand() else if (canModify) onToggleSelection(!isEffectivelySelected)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            hasChildren && isExpanded -> Icons.Filled.FolderOpen
                            isEffectivelySelected -> Icons.Filled.Folder
                            isPartiallySelected -> Icons.Filled.FolderSpecial
                            else -> Icons.Outlined.Folder
                        },
                        contentDescription = node.name,
                        tint = when {
                            isEffectivelySelected -> NcPrimaryBlue
                            isPartiallySelected -> NcCyanAccent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 3. Folder Name, Path & Sync State Badges
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { if (canModify) onToggleSelection(!isEffectivelySelected) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isEffectivelySelected || isPartiallySelected) FontWeight.Bold else FontWeight.Medium
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

                    // Sync Status Badges
                    if (isInherited) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Inherited",
                                tint = NcPrimaryBlue,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Included via parent (${node.parentSelectedName ?: "parent"})",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = NcPrimaryBlue
                            )
                        }
                    } else if (isPartiallySelected) {
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
                            text = "Folder + all $selectedDescendants subfolders synced",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = NcPrimaryBlue
                        )
                    }
                }

                // 4. Add Subfolder Action
                IconButton(
                    onClick = onAddSubfolder,
                    modifier = Modifier.size(32.dp).testTag("add_subfolder_btn_${node.name}")
                ) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = "Add subfolder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 5. Selection Checkbox
                Checkbox(
                    checked = isEffectivelySelected,
                    enabled = canModify,
                    onCheckedChange = { if (canModify) onToggleSelection(it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = if (isInherited) NcPrimaryBlue.copy(alpha = 0.7f) else NcPrimaryBlue
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
    isInheritedFromParent: Boolean,
    parentSelectedName: String?,
    canModify: Boolean = true,
    onToggleSelection: (Boolean) -> Unit,
    onAddSubfolder: () -> Unit
) {
    val isEffectivelySelected = folder.isSelected || isInheritedFromParent

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEffectivelySelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEffectivelySelected) 1.dp else 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (canModify) onToggleSelection(!isEffectivelySelected) }
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
                            if (isEffectivelySelected) NcPrimaryBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEffectivelySelected) Icons.Filled.Folder else Icons.Outlined.FolderOff,
                        contentDescription = folder.displayName,
                        tint = if (isEffectivelySelected) NcPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = folder.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isEffectivelySelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Path: ${folder.remotePath} • ${if (isEffectivelySelected) "Synchronized" else "Excluded"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isInheritedFromParent) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = NcPrimaryBlue,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Included via parent (${parentSelectedName ?: "parent"})",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = NcPrimaryBlue
                            )
                        }
                    }
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
                    checked = isEffectivelySelected,
                    enabled = canModify,
                    onCheckedChange = { if (canModify) onToggleSelection(it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = if (isInheritedFromParent) NcPrimaryBlue.copy(alpha = 0.7f) else NcPrimaryBlue
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

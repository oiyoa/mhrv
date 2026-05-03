package com.therealaleph.mhrv.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.MhrvConfig
import com.therealaleph.mhrv.SplitMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -------------------------------------------------------------------------
// Popular-app package names for prioritization.
// -------------------------------------------------------------------------
private val POPULAR_PACKAGES = listOf(
    "org.telegram.messenger",
    "com.twitter.android",
    "com.whatsapp",
    "com.instagram.android",
    "com.facebook.katana",
    "com.android.chrome",
    "com.google.android.youtube",
    "com.google.android.apps.tachyon",
    "com.google.android.apps.maps",
    "com.google.android.gm",
    "com.google.android.apps.docs",
    "com.google.android.apps.docs.editors.docs",
    "com.google.android.apps.photos",
    "com.google.android.calendar",
    "com.google.android.apps.messaging",
    "com.google.android.dialer",
    "com.google.android.apps.translate",
    "com.spotify.music",
    "com.netflix.mediaclient",
)

// -------------------------------------------------------------------------
// Data model — mirrors AppPickerDialog.AppEntry but kept independent
// so we don't modify upstream code.
// -------------------------------------------------------------------------
internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSplitButton(
    cfg: MhrvConfig,
    enabled: Boolean,
    onCfgChanged: (MhrvConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }

    val selectedCount = if (cfg.splitMode == SplitMode.ONLY) cfg.splitApps.size else 0
    val isFiltering = selectedCount > 0

    Surface(
        onClick = { if (enabled) sheetOpen = true },
        modifier = modifier.fillMaxWidth(),
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                       else if (isFiltering) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_split_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
                Text(
                    text = if (isFiltering)
                        stringResource(R.string.app_split_status_n, selectedCount)
                    else
                        stringResource(R.string.app_split_status_all),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                           else if (isFiltering) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!enabled) {
                Text(
                    text = "Disconnect to edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // ... (rest of AppSplitButton logic stays same)

    // -- The bottom-sheet picker -------------------------------------------
    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            AppSplitSheetContent(
                selectedPackages = if (cfg.splitMode == SplitMode.ONLY) cfg.splitApps else emptyList(),
                ownPackage = ctx.packageName,
                onSelectionChanged = { newList ->
                    val newMode = if (newList.isEmpty()) SplitMode.ALL else SplitMode.ONLY
                    onCfgChanged(cfg.copy(splitMode = newMode, splitApps = newList))
                },
            )
        }
    }
}

// -------------------------------------------------------------------------
// Bottom-sheet content: search + selected chips + scrollable app list.
// -------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSplitSheetContent(
    selectedPackages: List<String>,
    ownPackage: String,
    onSelectionChanged: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val pm = remember { ctx.packageManager }

    // Live selection state for the checkboxes.
    // Note: We use a local state for the UI, but we'll trigger the callback
    // immediately to persist changes as the user interacts.
    val selected = remember { mutableStateListOf<String>().apply { addAll(selectedPackages) } }
    
    // Capture initial selection to keep the list stable while the sheet is open.
    val initialSelectedSet = remember { selectedPackages.toSet() }
    val popularSet = remember { POPULAR_PACKAGES.toSet() }

    var allApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            loadInstalledApps(pm, ownPackage)
        }
        loading = false
    }

    // Filtered and stably sorted list.
    val displayed by remember {
        derivedStateOf {
            val filtered = if (query.isBlank()) allApps
            else allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            
            // Sort by initial selection first, then popular apps, then alphabetically.
            // This ensures the list doesn't "jump" while the user is interacting with it.
            filtered.sortedWith(
                compareByDescending<InstalledApp> { it.packageName in initialSelectedSet }
                    .thenByDescending { it.packageName in popularSet }
                    .thenBy { it.isSystem } // User apps (isSystem=false) before system apps (isSystem=true)
                    .thenBy { it.label.lowercase() }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        // Title row - Removed Done button as selection is now live.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_split_pick_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(R.string.app_split_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Search field
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.app_split_search)) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            leadingIcon = {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(20.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
        )

        Spacer(Modifier.height(16.dp))

        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(strokeWidth = 3.dp) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(displayed, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selected
                    AppPickerRow(
                        app = app,
                        isSelected = isSelected,
                        onToggle = {
                            if (isSelected) selected.remove(app.packageName)
                            else selected.add(app.packageName)
                            
                            // Immediately persist the new selection
                            onSelectionChanged(selected.toList())
                        },
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Row composables
// -------------------------------------------------------------------------

@Composable
private fun AppPickerRow(
    app: InstalledApp,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.icon, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = null, // Handled by Surface click
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun AppIcon(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.clip(MaterialTheme.shapes.small),
        )
    } else {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.padding(8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -------------------------------------------------------------------------
// App loading — duplicated from AppPickerDialog to avoid upstream changes.
// -------------------------------------------------------------------------

private fun loadInstalledApps(
    pm: PackageManager,
    ownPackage: String,
): List<InstalledApp> {
    val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    
    return pm.queryIntentActivities(mainIntent, 0)
        .asSequence()
        .mapNotNull { it.activityInfo?.applicationInfo }
        .filter { it.packageName != ownPackage }
        .distinctBy { it.packageName }
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = loadAppIcon(pm, info),
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .sortedWith(
            compareBy<InstalledApp> { it.isSystem }
                .thenBy { it.label.lowercase() }
        )
        .toList()
}

/**
 * Load an app icon as [ImageBitmap]. Returns null on failure — the UI
 * falls back to a generic icon.  Scales to 96×96 px.
 */
private fun loadAppIcon(pm: PackageManager, info: ApplicationInfo): ImageBitmap? {
    return try {
        pm.getApplicationIcon(info).toBitmap(width = 96, height = 96).asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

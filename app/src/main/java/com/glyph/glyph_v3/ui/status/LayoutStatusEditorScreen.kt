package com.glyph.glyph_v3.ui.status

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.TypedValue
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.ui.media.ZoomableImageView
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

private enum class LayoutTemplate(val label: String) {
    Grid2x2("2×2"),
    Vertical("2×1"),
    HeroTop("1+2"),
    HeroBottom("2+1"),
    Grid2x3("2×3"),
    Grid3x2("3×2")
}

private data class LayoutSlot(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

private data class TileTransformState(
    val uri: Uri,
    val scale: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

private enum class EditorTool {
    None,
    Draw
}

private enum class BrushStyle(val widthFraction: Float) {
    Fine(0.005f),
    Medium(0.011f),
    Bold(0.02f)
}

private data class DrawStroke(
    val points: List<Offset>,
    val color: Color,
    val brushStyle: BrushStyle
)

private data class MusicTrack(
    val title: String,
    val artist: String,
    val duration: String,
    val category: String
)

private sealed interface EditorOverlay {
    val id: String
    val xFraction: Float
    val yFraction: Float
    val scale: Float
    val rotation: Float
}

private data class TextOverlay(
    override val id: String,
    val text: String,
    override val xFraction: Float,
    override val yFraction: Float,
    override val scale: Float,
    override val rotation: Float,
    val textColor: Color = Color.White,
    val backgroundColor: Color = Color.Black.copy(alpha = 0.38f)
) : EditorOverlay

private data class EmojiOverlay(
    override val id: String,
    val emoji: String,
    override val xFraction: Float,
    override val yFraction: Float,
    override val scale: Float,
    override val rotation: Float
) : EditorOverlay

private data class LabelOverlay(
    override val id: String,
    val label: String,
    override val xFraction: Float,
    override val yFraction: Float,
    override val scale: Float,
    override val rotation: Float,
    val accent: Color = EditorAccentColor
) : EditorOverlay

private enum class ShapeType {
    Circle,
    Square,
    Arrow,
    Bubble
}

private data class ShapeOverlay(
    override val id: String,
    val shape: ShapeType,
    override val xFraction: Float,
    override val yFraction: Float,
    override val scale: Float,
    override val rotation: Float,
    val tint: Color = EditorAccentColor
) : EditorOverlay

private data class LayoutFilter(
    val label: String,
    val matrix: FloatArray?
)

private val editorFilters = listOf(
    LayoutFilter("Original", null),
    LayoutFilter("Mono", floatArrayOf(
        0.33f, 0.33f, 0.33f, 0f, 0f,
        0.33f, 0.33f, 0.33f, 0f, 0f,
        0.33f, 0.33f, 0.33f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )),
    LayoutFilter("Warm", floatArrayOf(
        1.08f, 0f, 0f, 0f, 10f,
        0f, 1.02f, 0f, 0f, 6f,
        0f, 0f, 0.92f, 0f, -8f,
        0f, 0f, 0f, 1f, 0f
    )),
    LayoutFilter("Cool", floatArrayOf(
        0.95f, 0f, 0f, 0f, -4f,
        0f, 1.0f, 0f, 0f, 0f,
        0f, 0f, 1.08f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f
    )),
    LayoutFilter("Fade", floatArrayOf(
        0.9f, 0f, 0f, 0f, 18f,
        0f, 0.9f, 0f, 0f, 18f,
        0f, 0f, 0.9f, 0f, 18f,
        0f, 0f, 0f, 1f, 0f
    ))
)

private val sampleTracks = listOf(
    MusicTrack("Saasein", "Sohini Mishra", "4:51", "Suggested"),
    MusicTrack("Deewaana Deewaana", "A.R. Rahman", "5:37", "Suggested"),
    MusicTrack("Sukoon", "Othoms", "5:05", "Mood"),
    MusicTrack("Dil Ka Rishta", "Nadeem-Shravan", "5:04", "Mood"),
    MusicTrack("Sitaare", "Arijit Singh", "4:00", "Genre"),
    MusicTrack("Meri Zindagi Hai Tu", "Asim Azhar", "3:46", "Genre")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutStatusEditorScreen(
    imageUris: List<Uri>,
    isUploading: Boolean,
    onSend: (Uri, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val theme = glyphTheme
    val scope = rememberCoroutineScope()

    val availableLayouts = remember(imageUris) { availableLayoutsFor(imageUris.size) }
    var selectedLayout by remember(imageUris) {
        mutableStateOf(availableLayouts.firstOrNull() ?: LayoutTemplate.Grid2x2)
    }
    val tileStates = remember(imageUris) {
        mutableStateListOf<TileTransformState>().apply {
            imageUris.forEach { add(TileTransformState(uri = it)) }
        }
    }
    val overlays = remember { mutableStateListOf<EditorOverlay>() }
    val drawStrokes = remember { mutableStateListOf<DrawStroke>() }

    var currentTool by remember { mutableStateOf(EditorTool.None) }
    var caption by remember { mutableStateOf("") }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    var showStickerSheet by remember { mutableStateOf(false) }
    var showMusicSheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf("") }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember { mutableStateOf(editorFilters.first()) }
    var selectedMusicTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var currentBrushStyle by remember { mutableStateOf(BrushStyle.Medium) }
    var brushColorProgress by remember { mutableFloatStateOf(0.18f) }
    var isRendering by remember { mutableStateOf(false) }

    val currentBrushColor = remember(brushColorProgress) {
        Color.hsv(hue = brushColorProgress * 360f, saturation = 0.92f, value = 1f)
    }
    val filterMatrix = remember(selectedFilter) { selectedFilter.matrix?.copyOf() }

    LaunchedEffect(selectedMusicTrack) {
        val music = selectedMusicTrack ?: return@LaunchedEffect
        val musicId = "music_overlay"
        val existingIndex = overlays.indexOfFirst { it.id == musicId }
        val updated = LabelOverlay(
            id = musicId,
            label = "♪ ${music.title}",
            xFraction = 0.08f,
            yFraction = 0.08f,
            scale = 1f,
            rotation = 0f,
            accent = theme.actionPrimary
        )
        if (existingIndex >= 0) {
            overlays[existingIndex] = updated
        } else {
            overlays.add(updated)
        }
        selectedOverlayId = musicId
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
                    .padding(top = 18.dp, bottom = 92.dp)
                    .fillMaxHeight(0.76f)
                    .aspectRatio(9f / 16f)   // match the 1080×1920 render canvas
                    .clip(RoundedCornerShape(18.dp))
                    .onSizeChanged { editorSize = it }
            ) {
                if (editorSize.width > 0 && editorSize.height > 0) {
                    val slots = remember(selectedLayout, tileStates.size) {
                        buildLayoutSlots(selectedLayout, tileStates.size)
                    }
                    val density = LocalDensity.current

                    slots.forEachIndexed { index, slot ->
                        val tile = tileStates.getOrNull(index) ?: return@forEachIndexed
                        val leftDp = with(density) { (editorSize.width * slot.left).toDp() }
                        val topDp = with(density) { (editorSize.height * slot.top).toDp() }
                        val widthDp = with(density) { (editorSize.width * slot.width).toDp() }
                        val heightDp = with(density) { (editorSize.height * slot.height).toDp() }

                        EditableImageTile(
                            tileState = tile,
                            slotWidthPx = editorSize.width * slot.width,
                            slotHeightPx = editorSize.height * slot.height,
                            filterMatrix = filterMatrix,
                            gesturesEnabled = currentTool != EditorTool.Draw,
                            onStateChanged = { updated -> tileStates[index] = updated },
                            modifier = Modifier
                                .offset(x = leftDp, y = topDp)
                                .size(widthDp, heightDp)
                        )
                    }

                    DrawingLayer(
                        strokes = drawStrokes,
                        active = currentTool == EditorTool.Draw,
                        editorSize = editorSize,
                        currentBrushColor = currentBrushColor,
                        brushStyle = currentBrushStyle,
                        modifier = Modifier.fillMaxSize(),
                        onStrokeCompleted = { stroke -> drawStrokes.add(stroke) }
                    )

                    overlays.forEachIndexed { index, overlay ->
                        MovableOverlay(
                            overlay = overlay,
                            editorSize = editorSize,
                            selected = overlay.id == selectedOverlayId,
                            zOrder = index.toFloat(),
                            modifier = Modifier.fillMaxSize(),
                            onSelected = { selectedOverlayId = overlay.id },
                            onUpdate = { updated -> overlays[index] = updated },
                            onBringForward = {
                                if (index < overlays.lastIndex) {
                                    val moved = overlays.removeAt(index)
                                    overlays.add(index + 1, moved)
                                }
                            },
                            onSendBackward = {
                                if (index > 0) {
                                    val moved = overlays.removeAt(index)
                                    overlays.add(index - 1, moved)
                                }
                            },
                            onDelete = {
                                overlays.removeAt(index)
                                if (selectedOverlayId == overlay.id) {
                                    selectedOverlayId = null
                                }
                            }
                        )
                    }
                }
            }

            LayoutStepTopBar(
                onBack = onBack,
                onDone = {
                    if (!isUploading && !isRendering && editorSize.width > 0 && editorSize.height > 0) {
                        val layout = selectedLayout
                        val filter = selectedFilter
                        val tilesSnapshot = tileStates.toList()
                        val overlaysSnapshot = overlays.toList()
                        val strokesSnapshot = drawStrokes.toList()
                        scope.launch {
                            isRendering = true
                            val uri = withContext(Dispatchers.IO) {
                                renderLayoutStatusToUri(
                                    context = context,
                                    layout = layout,
                                    tileStates = tilesSnapshot,
                                    overlays = overlaysSnapshot,
                                    strokes = strokesSnapshot,
                                    filter = filter
                                )
                            }
                            isRendering = false
                            onSend(uri, caption)
                        }
                    }
                },
                doneEnabled = !isUploading && !isRendering,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    items(availableLayouts) { layout ->
                        LayoutTemplateOption(
                            layout = layout,
                            selected = selectedLayout == layout,
                            onClick = { selectedLayout = layout }
                        )
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Add text") },
            text = {
                OutlinedTextField(
                    value = pendingText,
                    onValueChange = { pendingText = it },
                    placeholder = { Text("Type something") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pendingText.isNotBlank()) {
                            val overlay = TextOverlay(
                                id = UUID.randomUUID().toString(),
                                text = pendingText,
                                xFraction = 0.18f,
                                yFraction = 0.16f,
                                scale = 1f,
                                rotation = 0f
                            )
                            overlays.add(overlay)
                            selectedOverlayId = overlay.id
                        }
                        pendingText = ""
                        showTextDialog = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showStickerSheet) {
        StickerElementsSheet(
            onDismiss = { showStickerSheet = false },
            onAddOverlay = {
                overlays.add(it)
                selectedOverlayId = it.id
            }
        )
    }

    if (showMusicSheet) {
        MusicPickerSheet(
            selectedTrack = selectedMusicTrack,
            onDismiss = { showMusicSheet = false },
            onTrackSelected = {
                selectedMusicTrack = it
                showMusicSheet = false
            }
        )
    }

    if (showFilterSheet) {
        FilterPickerSheet(
            selectedFilter = selectedFilter,
            onDismiss = { showFilterSheet = false },
            onSelected = {
                selectedFilter = it
                showFilterSheet = false
            }
        )
    }
}

@Composable
private fun EditableImageTile(
    tileState: TileTransformState,
    slotWidthPx: Float,
    slotHeightPx: Float,
    filterMatrix: FloatArray?,
    gesturesEnabled: Boolean,
    onStateChanged: (TileTransformState) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val corner = RoundedCornerShape(8.dp)

    // Track the URI currently loaded in the AndroidView to avoid redundant Glide reloads
    // (each Glide into() triggers setImageDrawable → setupImage → full zoom reset).
    var loadedUri by remember { mutableStateOf<Uri?>(null) }

    Box(
        modifier = modifier
            .clip(corner)
            .background(Color(0xFF121212))
    ) {
        AndroidView(
            factory = { viewContext ->
                ZoomableImageView(viewContext).apply {
                    clipToOutline = true
                    setOnTransformChangedListener { scale, offsetX, offsetY, imageWidth, imageHeight ->
                        onStateChanged(
                            TileTransformState(
                                uri = tileState.uri,
                                scale = scale,
                                offsetXFraction = offsetX,
                                offsetYFraction = offsetY,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight
                            )
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { imageView ->
                imageView.isEnabled = gesturesEnabled
                // Only reload image when the URI actually changes to avoid resetting zoom state
                if (loadedUri != tileState.uri) {
                    loadedUri = tileState.uri
                    Glide.with(imageView)
                        .load(tileState.uri)
                        .into(imageView)
                    // Apply initial transform only on fresh load
                    imageView.setTransformState(
                        scale = tileState.scale,
                        offsetXFraction = tileState.offsetXFraction,
                        offsetYFraction = tileState.offsetYFraction
                    )
                }
                imageView.colorFilter = filterMatrix?.let {
                    android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it))
                }
            }
        )
    }
}

@Composable
private fun DrawingLayer(
    strokes: List<DrawStroke>,
    active: Boolean,
    editorSize: IntSize,
    currentBrushColor: Color,
    brushStyle: BrushStyle,
    modifier: Modifier = Modifier,
    onStrokeCompleted: (DrawStroke) -> Unit
) {
    var currentPoints by remember(active) { mutableStateOf<List<Offset>>(emptyList()) }

    ComposeCanvas(
        modifier = modifier.then(
            if (active && editorSize.width > 0 && editorSize.height > 0) {
                Modifier.pointerInput(editorSize, currentBrushColor, brushStyle) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPoints = listOf(offset.normalize(editorSize))
                        },
                        onDragCancel = { currentPoints = emptyList() },
                        onDragEnd = {
                            if (currentPoints.size > 1) {
                                onStrokeCompleted(DrawStroke(currentPoints, currentBrushColor, brushStyle))
                            }
                            currentPoints = emptyList()
                        }
                    ) { change, _ ->
                        change.consume()
                        currentPoints = currentPoints + change.position.normalize(editorSize)
                    }
                }
            } else {
                Modifier
            }
        )
    ) {
        strokes.forEach { stroke ->
            drawStrokePath(stroke, size)
        }
        if (currentPoints.isNotEmpty()) {
            drawStrokePath(DrawStroke(currentPoints, currentBrushColor, brushStyle), size)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    stroke: DrawStroke,
    canvasSize: Size
) {
    if (stroke.points.size < 2) return
    val path = androidx.compose.ui.graphics.Path().apply {
        val first = stroke.points.first().denormalize(canvasSize)
        moveTo(first.x, first.y)
        stroke.points.drop(1).forEach { point ->
            val denorm = point.denormalize(canvasSize)
            lineTo(denorm.x, denorm.y)
        }
    }
    drawPath(
        path = path,
        color = stroke.color,
        style = Stroke(
            width = stroke.brushStyle.widthFraction * canvasSize.minDimension,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = null
        )
    )
}

@Composable
private fun MovableOverlay(
    overlay: EditorOverlay,
    editorSize: IntSize,
    selected: Boolean,
    zOrder: Float,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit,
    onUpdate: (EditorOverlay) -> Unit,
    onBringForward: () -> Unit,
    onSendBackward: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val baseSize = overlayBaseSize(overlay)
    val widthPx = with(density) { baseSize.width.toPx() }
    val heightPx = with(density) { baseSize.height.toPx() }

    Box(
        modifier = modifier
            .zIndex(zOrder + if (selected) 100f else 0f)
            .offset(
                x = with(density) { (overlay.xFraction * editorSize.width).toDp() },
                y = with(density) { (overlay.yFraction * editorSize.height).toDp() }
            )
            .size(baseSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelected
            )
            .graphicsLayer {
                scaleX = overlay.scale
                scaleY = overlay.scale
                rotationZ = overlay.rotation
            }
            .pointerInput(overlay.id, editorSize, widthPx, heightPx) {
                var currentScale = overlay.scale
                var currentX = overlay.xFraction
                var currentY = overlay.yFraction
                var currentRotation = overlay.rotation

                detectTransformGestures { _, pan, zoom, rotation ->
                    val nextScale = (currentScale * zoom).coerceIn(0.6f, 3.5f)
                    val scaledWidth = widthPx * nextScale
                    val scaledHeight = heightPx * nextScale
                    val maxLeft = (editorSize.width - scaledWidth).coerceAtLeast(0f)
                    val maxTop = (editorSize.height - scaledHeight).coerceAtLeast(0f)
                    val currentLeft = currentX * editorSize.width
                    val currentTop = currentY * editorSize.height
                    val nextLeft = (currentLeft + pan.x).coerceIn(0f, maxLeft)
                    val nextTop = (currentTop + pan.y).coerceIn(0f, maxTop)
                    currentScale = nextScale
                    currentX = if (editorSize.width == 0) 0f else nextLeft / editorSize.width
                    currentY = if (editorSize.height == 0) 0f else nextTop / editorSize.height
                    currentRotation += rotation
                    onUpdate(
                        overlay.updateTransform(
                            xFraction = currentX,
                            yFraction = currentY,
                            scale = currentScale,
                            rotation = currentRotation
                        )
                    )
                }
            }
    ) {
        if (selected) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-38).dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    MiniOverlayActionButton(Icons.Default.ArrowDownward, onSendBackward)
                    MiniOverlayActionButton(Icons.Default.ArrowUpward, onBringForward)
                    MiniOverlayActionButton(Icons.Default.DeleteOutline, onDelete)
                }
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
            )
        }

        when (overlay) {
            is TextOverlay -> PreviewTextOverlay(overlay)
            is EmojiOverlay -> PreviewEmojiOverlay(overlay)
            is LabelOverlay -> PreviewLabelOverlay(overlay)
            is ShapeOverlay -> PreviewShapeOverlay(overlay)
        }
    }
}

@Composable
private fun MiniOverlayActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PreviewTextOverlay(overlay: TextOverlay) {
    Surface(
        color = overlay.backgroundColor,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = overlay.text,
                color = overlay.textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PreviewEmojiOverlay(overlay: EmojiOverlay) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Text(text = overlay.emoji, fontSize = 54.sp)
    }
}

@Composable
private fun PreviewLabelOverlay(overlay: LabelOverlay) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = overlay.label,
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun PreviewShapeOverlay(overlay: ShapeOverlay) {
    val tint = overlay.tint
    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
        when (overlay.shape) {
            ShapeType.Circle -> drawCircle(color = tint, radius = size.minDimension / 2.6f, style = Stroke(width = 8f))
            ShapeType.Square -> drawRect(color = tint, size = Size(size.width * 0.7f, size.height * 0.7f), topLeft = Offset(size.width * 0.15f, size.height * 0.15f), style = Stroke(width = 8f))
            ShapeType.Arrow -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.82f)
                    lineTo(size.width * 0.76f, size.height * 0.24f)
                    lineTo(size.width * 0.58f, size.height * 0.24f)
                    moveTo(size.width * 0.76f, size.height * 0.24f)
                    lineTo(size.width * 0.76f, size.height * 0.42f)
                }
                drawPath(path, tint, style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ShapeType.Bubble -> {
                drawRoundRect(
                    color = Color(0xFFF8F4D6),
                    topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                    size = Size(size.width * 0.78f, size.height * 0.66f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f)
                )
                val tail = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.74f)
                    lineTo(size.width * 0.18f, size.height * 0.95f)
                    lineTo(size.width * 0.36f, size.height * 0.82f)
                    close()
                }
                drawPath(tail, Color(0xFFF8F4D6))
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                    size = Size(size.width * 0.78f, size.height * 0.66f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f),
                    style = Stroke(width = 8f)
                )
            }
        }
    }
}

@Composable
private fun LayoutStepTopBar(
    onBack: () -> Unit,
    onDone: () -> Unit,
    doneEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.36f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            onClick = onDone,
            enabled = doneEnabled,
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            modifier = Modifier.height(56.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 28.dp)
            ) {
                Text(
                    text = "Done",
                    color = Color.Black,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TopEditorToolbar(
    selectedMusic: MusicTrack?,
    onClose: () -> Unit,
    onMusic: () -> Unit,
    onSticker: () -> Unit,
    onText: () -> Unit,
    onDraw: () -> Unit,
    drawActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleToolbarButton(icon = Icons.Default.Close, onClick = onClose)
        Spacer(modifier = Modifier.weight(1f))
        CircleToolbarButton(icon = Icons.Default.MusicNote, onClick = onMusic, active = selectedMusic != null)
        Spacer(modifier = Modifier.width(8.dp))
        CircleToolbarButton(icon = Icons.Default.AutoAwesome, onClick = onSticker)
        Spacer(modifier = Modifier.width(8.dp))
        CircleToolbarButton(icon = Icons.Default.TextFields, onClick = onText)
        Spacer(modifier = Modifier.width(8.dp))
        CircleToolbarButton(icon = Icons.Default.Brush, onClick = onDraw, active = drawActive)
    }
}

@Composable
private fun CircleToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) glyphTheme.actionPrimary else Color.Black.copy(alpha = 0.42f),
        modifier = Modifier.size(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun LayoutTemplateOption(
    layout: LayoutTemplate,
    selected: Boolean,
    onClick: () -> Unit
) {
    val theme = glyphTheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.08f),
        modifier = Modifier.size(54.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            MiniLayoutGlyph(layout = layout, selected = selected)
        }
    }
}

@Composable
private fun MiniLayoutGlyph(layout: LayoutTemplate, selected: Boolean) {
    val fill = if (selected) Color.Black else Color.White
    val gap = 2.dp
    val size = 30.dp
    when (layout) {
        LayoutTemplate.Grid2x2 -> {
            Column(modifier = Modifier.size(size), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        LayoutTemplate.Vertical -> {
            Column(modifier = Modifier.size(size), verticalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(fill, RoundedCornerShape(1.dp)))
                Box(Modifier.weight(1f).fillMaxWidth().background(fill, RoundedCornerShape(1.dp)))
            }
        }
        LayoutTemplate.HeroTop -> {
            Column(modifier = Modifier.size(size), verticalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(2f).fillMaxWidth().background(fill, RoundedCornerShape(1.dp)))
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                }
            }
        }
        LayoutTemplate.HeroBottom -> {
            Column(modifier = Modifier.size(size), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                }
                Box(Modifier.weight(2f).fillMaxWidth().background(fill, RoundedCornerShape(1.dp)))
            }
        }
        LayoutTemplate.Grid2x3 -> {
            Column(modifier = Modifier.size(size), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(3) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(fill, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        LayoutTemplate.Grid3x2 -> {
            Row(modifier = Modifier.size(size), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(3) {
                            Box(Modifier.weight(1f).fillMaxWidth().background(fill, RoundedCornerShape(1.dp)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawingControls(
    brushProgress: Float,
    onBrushProgressChange: (Float) -> Unit,
    currentBrushStyle: BrushStyle,
    onBrushStyleChange: (BrushStyle) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VerticalHuePicker(
            progress = brushProgress,
            onProgressChange = onBrushProgressChange,
            modifier = Modifier
                .width(28.dp)
                .height(210.dp)
        )
        BrushStyle.entries.forEach { style ->
            Surface(
                onClick = { onBrushStyleChange(style) },
                shape = CircleShape,
                color = if (style == currentBrushStyle) Color.White else Color.White.copy(alpha = 0.18f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size((style.widthFraction * 900).dp.coerceIn(6.dp, 18.dp))
                            .clip(CircleShape)
                            .background(if (style == currentBrushStyle) Color.Black else Color.White)
                    )
                }
            }
        }
        CircleToolbarButton(icon = Icons.Default.Close, onClick = onUndo)
    }
}

@Composable
private fun VerticalHuePicker(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val rainbow = listOf(
        Color.Red,
        Color.Yellow,
        Color.Green,
        Color.Cyan,
        Color.Blue,
        Color.Magenta,
        Color.Red
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(rainbow))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onProgressChange((offset.y / size.height).coerceIn(0f, 1f))
                    }
                ) { change, _ ->
                    change.consume()
                    onProgressChange((change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (progress * 190f).dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, Color.Black, CircleShape)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerElementsSheet(
    onDismiss: () -> Unit,
    onAddOverlay: (EditorOverlay) -> Unit
) {
    val theme = glyphTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.backgroundPrimary,
        contentColor = theme.textPrimary,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .navigationBarsPadding()
        ) {
            Text("Shapes", style = MaterialTheme.typography.titleMedium, color = theme.textSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ShapeType.entries.forEach { shape ->
                    Surface(
                        onClick = {
                            onAddOverlay(
                                ShapeOverlay(
                                    id = UUID.randomUUID().toString(),
                                    shape = shape,
                                    xFraction = 0.2f,
                                    yFraction = 0.24f,
                                    scale = 1f,
                                    rotation = 0f
                                )
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = theme.backgroundElevated,
                        modifier = Modifier.weight(1f).height(74.dp)
                    ) {
                        PreviewShapeOverlay(
                            ShapeOverlay("preview", shape, 0f, 0f, 1f, 0f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("Elements", style = MaterialTheme.typography.titleMedium, color = theme.textSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("Add yours", "Location", "Photo", "Music", "Question", "Reaction", currentTimeLabel()).forEach { label ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            onAddOverlay(
                                LabelOverlay(
                                    id = UUID.randomUUID().toString(),
                                    label = label,
                                    xFraction = 0.16f,
                                    yFraction = 0.28f,
                                    scale = 1f,
                                    rotation = 0f
                                )
                            )
                            onDismiss()
                        },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("Stickers", style = MaterialTheme.typography.titleMedium, color = theme.textSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("☕", "😍", "🔥", "🌈", "✨", "😂", "🎉").forEach { emoji ->
                    Surface(
                        onClick = {
                            onAddOverlay(
                                EmojiOverlay(
                                    id = UUID.randomUUID().toString(),
                                    emoji = emoji,
                                    xFraction = 0.24f,
                                    yFraction = 0.34f,
                                    scale = 1f,
                                    rotation = 0f
                                )
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = theme.backgroundElevated,
                        modifier = Modifier.size(58.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = emoji, fontSize = 28.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicPickerSheet(
    selectedTrack: MusicTrack?,
    onDismiss: () -> Unit,
    onTrackSelected: (MusicTrack) -> Unit
) {
    val theme = glyphTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentCategory by remember { mutableStateOf("Suggested") }
    val tracks = remember(currentCategory) {
        sampleTracks.filter { currentCategory == "Suggested" || it.category == currentCategory }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.backgroundPrimary,
        contentColor = theme.textPrimary,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            Surface(color = theme.backgroundElevated, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = theme.textSecondary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Search genres", color = theme.textSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Suggested", "Mood", "Genre").forEach { category ->
                    FilterChip(
                        selected = currentCategory == category,
                        onClick = { currentCategory = category },
                        label = { Text(category) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(tracks) { track ->
                    Surface(
                        onClick = { onTrackSelected(track) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (selectedTrack == track) theme.backgroundElevated else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(12.dp), color = theme.backgroundElevated, modifier = Modifier.size(52.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = theme.actionPrimary)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium, color = theme.textPrimary)
                                Text(
                                    "${track.artist} · ${track.duration}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            CircleToolbarButton(icon = Icons.AutoMirrored.Filled.Send, onClick = { onTrackSelected(track) }, active = selectedTrack == track)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterPickerSheet(
    selectedFilter: LayoutFilter,
    onDismiss: () -> Unit,
    onSelected: (LayoutFilter) -> Unit
) {
    val theme = glyphTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.backgroundPrimary,
        contentColor = theme.textPrimary,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .navigationBarsPadding()
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, color = theme.textPrimary)
            Spacer(modifier = Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(editorFilters) { filter ->
                    Surface(
                        onClick = { onSelected(filter) },
                        shape = RoundedCornerShape(18.dp),
                        color = if (filter == selectedFilter) theme.actionPrimary else theme.backgroundElevated,
                        modifier = Modifier.size(width = 110.dp, height = 70.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = filter.label,
                                color = if (filter == selectedFilter) Color.Black else theme.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun availableLayoutsFor(count: Int): List<LayoutTemplate> {
    return when {
        count <= 2 -> listOf(LayoutTemplate.Grid2x2, LayoutTemplate.Vertical)
        count == 3 -> listOf(LayoutTemplate.Grid2x2, LayoutTemplate.Vertical, LayoutTemplate.HeroTop, LayoutTemplate.HeroBottom)
        count == 4 -> listOf(LayoutTemplate.Grid2x2, LayoutTemplate.HeroTop, LayoutTemplate.HeroBottom, LayoutTemplate.Grid2x3)
        else -> LayoutTemplate.entries.toList()
    }
}

private fun buildLayoutSlots(layout: LayoutTemplate, count: Int): List<LayoutSlot> {
    val gap = 0.008f
    return when (layout) {
        LayoutTemplate.Grid2x2 -> listOf(
            LayoutSlot(0f, 0f, 0.5f - gap / 2, 0.5f - gap / 2),
            LayoutSlot(0.5f + gap / 2, 0f, 0.5f - gap / 2, 0.5f - gap / 2),
            LayoutSlot(0f, 0.5f + gap / 2, 0.5f - gap / 2, 0.5f - gap / 2),
            LayoutSlot(0.5f + gap / 2, 0.5f + gap / 2, 0.5f - gap / 2, 0.5f - gap / 2)
        ).take(count)
        LayoutTemplate.Vertical -> List(count.coerceAtMost(3)) { index ->
            val each = (1f - gap * (count - 1)) / count
            LayoutSlot(0f, index * (each + gap), 1f, each)
        }
        LayoutTemplate.HeroTop -> listOf(
            LayoutSlot(0f, 0f, 1f, 0.58f),
            LayoutSlot(0f, 0.58f + gap, 0.5f - gap / 2, 0.42f - gap),
            LayoutSlot(0.5f + gap / 2, 0.58f + gap, 0.5f - gap / 2, 0.42f - gap)
        ).take(count)
        LayoutTemplate.HeroBottom -> listOf(
            LayoutSlot(0f, 0f, 0.5f - gap / 2, 0.42f - gap),
            LayoutSlot(0.5f + gap / 2, 0f, 0.5f - gap / 2, 0.42f - gap),
            LayoutSlot(0f, 0.42f + gap, 1f, 0.58f - gap)
        ).take(count)
        LayoutTemplate.Grid2x3 -> listOf(
            LayoutSlot(0f, 0f, 0.5f - gap / 2, (1f - 2 * gap) / 3f),
            LayoutSlot(0.5f + gap / 2, 0f, 0.5f - gap / 2, (1f - 2 * gap) / 3f),
            LayoutSlot(0f, (1f - 2 * gap) / 3f + gap, 0.5f - gap / 2, (1f - 2 * gap) / 3f),
            LayoutSlot(0.5f + gap / 2, (1f - 2 * gap) / 3f + gap, 0.5f - gap / 2, (1f - 2 * gap) / 3f),
            LayoutSlot(0f, 2f * ((1f - 2 * gap) / 3f + gap), 0.5f - gap / 2, (1f - 2 * gap) / 3f),
            LayoutSlot(0.5f + gap / 2, 2f * ((1f - 2 * gap) / 3f + gap), 0.5f - gap / 2, (1f - 2 * gap) / 3f)
        ).take(count)
        LayoutTemplate.Grid3x2 -> listOf(
            LayoutSlot(0f, 0f, (1f - gap) / 2f, (1f - 2 * gap) / 3f),
            LayoutSlot(0.5f + gap / 2, 0f, (1f - gap) / 2f, (1f - 2 * gap) / 3f),
            LayoutSlot(0f, (1f - 2 * gap) / 3f + gap, (1f - gap) / 2f, (1f - 2 * gap) / 3f),
            LayoutSlot(0.5f + gap / 2, (1f - 2 * gap) / 3f + gap, (1f - gap) / 2f, (1f - 2 * gap) / 3f),
            LayoutSlot(0f, 2f * ((1f - 2 * gap) / 3f + gap), (1f - gap) / 2f, (1f - 2 * gap) / 3f),
            LayoutSlot(0.5f + gap / 2, 2f * ((1f - 2 * gap) / 3f + gap), (1f - gap) / 2f, (1f - 2 * gap) / 3f)
        ).take(count)
    }
}

private fun overlayBaseSize(overlay: EditorOverlay): DpSize {
    return when (overlay) {
        is TextOverlay -> DpSize(180.dp, 72.dp)
        is EmojiOverlay -> DpSize(88.dp, 88.dp)
        is LabelOverlay -> DpSize(170.dp, 58.dp)
        is ShapeOverlay -> DpSize(110.dp, 110.dp)
    }
}

private fun EditorOverlay.updateTransform(
    xFraction: Float,
    yFraction: Float,
    scale: Float,
    rotation: Float
): EditorOverlay {
    return when (this) {
        is TextOverlay -> copy(xFraction = xFraction, yFraction = yFraction, scale = scale, rotation = rotation)
        is EmojiOverlay -> copy(xFraction = xFraction, yFraction = yFraction, scale = scale, rotation = rotation)
        is LabelOverlay -> copy(xFraction = xFraction, yFraction = yFraction, scale = scale, rotation = rotation)
        is ShapeOverlay -> copy(xFraction = xFraction, yFraction = yFraction, scale = scale, rotation = rotation)
    }
}

private fun Offset.normalize(size: IntSize): Offset {
    return Offset((x / size.width).coerceIn(0f, 1f), (y / size.height).coerceIn(0f, 1f))
}

private fun Offset.denormalize(size: Size): Offset {
    return Offset(x * size.width, y * size.height)
}

private fun renderLayoutStatusToUri(
    context: Context,
    layout: LayoutTemplate,
    tileStates: List<TileTransformState>,
    overlays: List<EditorOverlay>,
    strokes: List<DrawStroke>,
    filter: LayoutFilter
): Uri {
    val width = 1080
    val height = 1920
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.BLACK)

    val slots = buildLayoutSlots(layout, tileStates.size)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        filter.matrix?.let { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
    }

    slots.forEachIndexed { index, slot ->
        val tile = tileStates.getOrNull(index) ?: return@forEachIndexed
        val bitmapForTile = decodeBitmapForRender(context, tile.uri, width = 1200)
        val slotRect = RectF(
            slot.left * width,
            slot.top * height,
            (slot.left + slot.width) * width,
            (slot.top + slot.height) * height
        )
        val srcRect = computeCropRect(bitmapForTile, slotRect, tile)
        canvas.drawBitmap(bitmapForTile, srcRect, slotRect, paint)
        bitmapForTile.recycle()
    }

    renderOverlayElements(canvas, overlays, width.toFloat(), height.toFloat())
    renderDrawStrokes(canvas, strokes, width.toFloat(), height.toFloat())

    val outputDir = File(context.cacheDir, "layout_status").apply { mkdirs() }
    val file = File(outputDir, "layout_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
    }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun decodeBitmapForRender(context: Context, uri: Uri, width: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, width, width)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: return Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)

    // Apply EXIF orientation so the bitmap matches what Glide shows in the editor
    val rotation = try {
        context.contentResolver.openInputStream(uri)?.use { exifStream ->
            val exif = ExifInterface(exifStream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 0f
                else -> 0f
            }
        } ?: 0f
    } catch (_: Exception) { 0f }

    if (rotation == 0f) return raw

    val matrix = AndroidMatrix().apply { postRotate(rotation) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated !== raw) raw.recycle()
    return rotated
}

private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    var sampleSize = 1
    var halfWidth = srcWidth / 2
    var halfHeight = srcHeight / 2
    while (halfWidth / sampleSize >= reqWidth && halfHeight / sampleSize >= reqHeight) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

private fun computeCropRect(bitmap: Bitmap, slotRect: RectF, tile: TileTransformState): android.graphics.Rect {
    val slotW = slotRect.width()
    val slotH = slotRect.height()
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()

    // Use the editor's Glide-reported image dimensions when available so that
    // the center-crop direction (which axis is limiting) is identical to the
    // editor's ZoomableImageView.setupImage.  Fall back to the renderer bitmap
    // dimensions when the editor values are unavailable (tile never touched).
    val imgW = if (tile.imageWidth > 0) tile.imageWidth.toFloat() else bw
    val imgH = if (tile.imageHeight > 0) tile.imageHeight.toFloat() else bh

    // ── Replicate the exact ZoomableImageView.setupImage math ──
    // baseScale = max(viewW/intrinsicW, viewH/intrinsicH)  →  center-crop fit
    val baseScale = maxOf(slotW / imgW, slotH / imgH)
    val origW = imgW * baseScale          // fitted image width  in display coords
    val origH = imgH * baseScale          // fitted image height in display coords

    // ── Replicate notifyTransformChanged to reconstruct transX/transY ──
    val S = tile.scale                  // user zoom on top of base
    val contentW = origW * S
    val contentH = origH * S
    val centeredX = (slotW - contentW) / 2f
    val centeredY = (slotH - contentH) / 2f
    val maxShiftX = maxOf(0f, (contentW - slotW) / 2f)
    val maxShiftY = maxOf(0f, (contentH - slotH) / 2f)
    val transX = centeredX + tile.offsetXFraction.coerceIn(-1f, 1f) * maxShiftX
    val transY = centeredY + tile.offsetYFraction.coerceIn(-1f, 1f) * maxShiftY

    // ── Invert the matrix: visible region in editor-image coordinates ──
    val totalScale = baseScale * S
    val visLeft   = (0f    - transX) / totalScale
    val visTop    = (0f    - transY) / totalScale
    val visRight  = (slotW - transX) / totalScale
    val visBottom = (slotH - transY) / totalScale

    // Map from editor-image space to renderer-bitmap space and clamp
    val sx = bw / imgW
    val sy = bh / imgH
    val left   = (visLeft   * sx).coerceIn(0f, bw)
    val top    = (visTop    * sy).coerceIn(0f, bh)
    val right  = (visRight  * sx).coerceIn(0f, bw)
    val bottom = (visBottom * sy).coerceIn(0f, bh)

    return android.graphics.Rect(
        left.roundToInt(),
        top.roundToInt(),
        right.roundToInt().coerceAtLeast(left.roundToInt() + 1),
        bottom.roundToInt().coerceAtLeast(top.roundToInt() + 1)
    )
}

private fun renderDrawStrokes(canvas: Canvas, strokes: List<DrawStroke>, width: Float, height: Float) {
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = stroke.color.toArgbInt()
            strokeWidth = stroke.brushStyle.widthFraction * minOf(width, height)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(stroke.points.first().x * width, stroke.points.first().y * height)
            stroke.points.drop(1).forEach { point ->
                lineTo(point.x * width, point.y * height)
            }
        }
        canvas.drawPath(path, paint)
    }
}

private fun renderOverlayElements(canvas: Canvas, overlays: List<EditorOverlay>, width: Float, height: Float) {
    overlays.forEach { overlay ->
        when (overlay) {
            is TextOverlay -> drawTextOverlay(canvas, overlay, width, height)
            is EmojiOverlay -> drawEmojiOverlay(canvas, overlay, width, height)
            is LabelOverlay -> drawLabelOverlay(canvas, overlay, width, height)
            is ShapeOverlay -> drawShapeOverlay(canvas, overlay, width, height)
        }
    }
}

private fun drawTextOverlay(canvas: Canvas, overlay: TextOverlay, width: Float, height: Float) {
    val rect = overlayRect(overlay, width, height, 320f, 120f)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlay.backgroundColor.toArgbInt() }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = overlay.textColor.toArgbInt()
        textSize = 56f * overlay.scale
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.save()
    canvas.rotate(overlay.rotation, rect.centerX(), rect.centerY())
    canvas.drawRoundRect(rect, 40f, 40f, bgPaint)
    val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(overlay.text, rect.centerX(), textY, textPaint)
    canvas.restore()
}

private fun drawEmojiOverlay(canvas: Canvas, overlay: EmojiOverlay, width: Float, height: Float) {
    val rect = overlayRect(overlay, width, height, 120f, 120f)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 94f * overlay.scale
        textAlign = Paint.Align.CENTER
    }
    canvas.save()
    canvas.rotate(overlay.rotation, rect.centerX(), rect.centerY())
    val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(overlay.emoji, rect.centerX(), baseline, textPaint)
    canvas.restore()
}

private fun drawLabelOverlay(canvas: Canvas, overlay: LabelOverlay, width: Float, height: Float) {
    val rect = overlayRect(overlay, width, height, 340f, 96f)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 42f * overlay.scale
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.save()
    canvas.rotate(overlay.rotation, rect.centerX(), rect.centerY())
    canvas.drawRoundRect(rect, 44f, 44f, bgPaint)
    val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(overlay.label, rect.centerX(), baseline, textPaint)
    canvas.restore()
}

private fun drawShapeOverlay(canvas: Canvas, overlay: ShapeOverlay, width: Float, height: Float) {
    val rect = overlayRect(overlay, width, height, 180f, 180f)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = overlay.tint.toArgbInt()
        style = Paint.Style.STROKE
        strokeWidth = 10f * overlay.scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = overlay.tint.copy(alpha = 0.12f).toArgbInt()
        style = Paint.Style.FILL
    }
    canvas.save()
    canvas.rotate(overlay.rotation, rect.centerX(), rect.centerY())
    when (overlay.shape) {
        ShapeType.Circle -> canvas.drawOval(rect, stroke)
        ShapeType.Square -> canvas.drawRect(rect, stroke)
        ShapeType.Arrow -> {
            canvas.drawLine(rect.left + 16f, rect.bottom - 16f, rect.right - 28f, rect.top + 28f, stroke)
            canvas.drawLine(rect.right - 28f, rect.top + 28f, rect.right - 64f, rect.top + 28f, stroke)
            canvas.drawLine(rect.right - 28f, rect.top + 28f, rect.right - 28f, rect.top + 64f, stroke)
        }
        ShapeType.Bubble -> {
            val bubble = RectF(rect.left, rect.top, rect.right - 28f, rect.bottom - 36f)
            canvas.drawRoundRect(bubble, 40f, 40f, fill)
            canvas.drawRoundRect(bubble, 40f, 40f, stroke)
            val path = Path().apply {
                moveTo(bubble.left + 48f, bubble.bottom)
                lineTo(bubble.left + 18f, bubble.bottom + 46f)
                lineTo(bubble.left + 82f, bubble.bottom + 18f)
                close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }
    canvas.restore()
}

private fun overlayRect(
    overlay: EditorOverlay,
    width: Float,
    height: Float,
    baseWidth: Float,
    baseHeight: Float
): RectF {
    val scaledWidth = baseWidth * overlay.scale
    val scaledHeight = baseHeight * overlay.scale
    val left = overlay.xFraction * width
    val top = overlay.yFraction * height
    return RectF(left, top, left + scaledWidth, top + scaledHeight)
}

private fun calculateTilePanBounds(
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
    slotWidthPx: Float,
    slotHeightPx: Float
): Pair<Float, Float> {
    val safeImageWidth = imageWidth.takeIf { it > 0 }?.toFloat() ?: slotWidthPx
    val safeImageHeight = imageHeight.takeIf { it > 0 }?.toFloat() ?: slotHeightPx
    val slotAspect = if (slotHeightPx == 0f) 1f else slotWidthPx / slotHeightPx
    val imageAspect = if (safeImageHeight == 0f) slotAspect else safeImageWidth / safeImageHeight

    val baseRenderedWidth: Float
    val baseRenderedHeight: Float
    if (imageAspect > slotAspect) {
        baseRenderedHeight = slotHeightPx
        baseRenderedWidth = slotHeightPx * imageAspect
    } else {
        baseRenderedWidth = slotWidthPx
        baseRenderedHeight = if (imageAspect == 0f) slotHeightPx else slotWidthPx / imageAspect
    }

    val scaledWidth = baseRenderedWidth * scale
    val scaledHeight = baseRenderedHeight * scale
    val maxPanX = ((scaledWidth - slotWidthPx) / 2f).coerceAtLeast(0f)
    val maxPanY = ((scaledHeight - slotHeightPx) / 2f).coerceAtLeast(0f)
    return maxPanX to maxPanY
}

@Composable
private fun captionFieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = Color.White,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

private fun Color.toArgbInt(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt()
    )
}

private fun currentTimeLabel(): String = "12:37 am"

private val EditorAccentColor = Color(0xFF25D366)
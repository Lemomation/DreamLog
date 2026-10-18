package com.lemomation.dreamlog.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemomation.dreamlog.DreamLogViewModel
import com.lemomation.dreamlog.GraphEdgeModel
import com.lemomation.dreamlog.GraphModel
import com.lemomation.dreamlog.GraphNodeKind
import com.lemomation.dreamlog.GraphNodeModel
import com.lemomation.dreamlog.ui.components.Hairline
import com.lemomation.dreamlog.ui.theme.DreamColors
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    viewModel: DreamLogViewModel,
    focusConceptId: Long?,
    onOpenNote: (Long) -> Unit,
) {
    val graph by viewModel.graph.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()

    val physics = remember { GraphPhysics() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var sheetConceptId by remember { mutableStateOf<Long?>(null) }
    var wakeToken by remember { mutableIntStateOf(0) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var handledFocus by remember { mutableLongStateOf(-1L) }
    val labelCache = remember { mutableMapOf<String, androidx.compose.ui.text.TextLayoutResult>() }

    val graphSignature = remember(graph) {
        graph.nodes.joinToString(separator = "|") { "${it.key}:${it.label}" } +
            "#" + graph.edges.joinToString(separator = "|") { "${it.fromKey}>${it.toKey}" }
    }

    LaunchedEffect(graphSignature, canvasSize, density) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        labelCache.clear()
        physics.load(
            model = graph,
            width = canvasSize.width.toFloat(),
            height = canvasSize.height.toFloat(),
            density = density,
        )
        zoom = 1f
        pan = Offset.Zero
        selectedKey = null
        wakeToken += 1
    }

    LaunchedEffect(physics, canvasSize, wakeToken, density) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        while (isActive && physics.alpha > 0.0001f) {
            withFrameNanos { }
            physics.step(
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
                density = density,
            )
        }
    }

    LaunchedEffect(focusConceptId, graph.nodes.size, graph.edges.size) {
        val conceptId = focusConceptId ?: return@LaunchedEffect
        if (conceptId <= 0L || handledFocus == conceptId) return@LaunchedEffect
        val node = graph.nodes.firstOrNull {
            it.kind == GraphNodeKind.CONCEPT && it.conceptId == conceptId
        } ?: return@LaunchedEffect
        handledFocus = conceptId
        selectedKey = node.key
        sheetConceptId = conceptId
    }

    val sheetConcept = sheetConceptId?.let { id ->
        graph.nodes.firstOrNull { it.kind == GraphNodeKind.CONCEPT && it.conceptId == id }
    }
    val sheetNotes = sheetConcept?.let { concept ->
        graph.edges.mapNotNull { edge ->
            val otherKey = when (concept.key) {
                edge.fromKey -> edge.toKey
                edge.toKey -> edge.fromKey
                else -> null
            }
            otherKey?.let { key ->
                graph.nodes.firstOrNull { it.key == key && it.kind == GraphNodeKind.NOTE }
            }
        }.distinctBy { it.key }
    }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DreamColors.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Graph",
                color = DreamColors.TextPrimary,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
        }
        Hairline()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DreamColors.Background),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(physics) {
                        detectTapGestures { tap ->
                            val world = screenToWorld(
                                screen = tap,
                                viewport = Size(size.width.toFloat(), size.height.toFloat()),
                                zoom = zoom,
                                pan = pan,
                            )
                            val hit = physics.nearest(world, 24.dp.toPx() / zoom)
                            if (hit != null) {
                                selectedKey = hit.key
                                val model = graph.nodes.firstOrNull { it.key == hit.key }
                                when (model?.kind) {
                                    GraphNodeKind.NOTE -> {
                                        model.noteId?.let(onOpenNote)
                                    }

                                    GraphNodeKind.CONCEPT -> {
                                        sheetConceptId = model.conceptId
                                    }

                                    null -> Unit
                                }
                            } else {
                                selectedKey = null
                            }
                        }
                    }
                    .pointerInput(physics) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val world = screenToWorld(
                                    screen = start,
                                    viewport = Size(size.width.toFloat(), size.height.toFloat()),
                                    zoom = zoom,
                                    pan = pan,
                                )
                                val body = physics.nearest(world, 24.dp.toPx() / zoom)
                                draggedKey = body?.key
                                if (body != null) {
                                    selectedKey = body.key
                                    physics.alpha = max(physics.alpha, 0.3f)
                                    wakeToken += 1
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val body = draggedKey?.let { key ->
                                    physics.bodies.firstOrNull { it.key == key }
                                }
                                if (body != null) {
                                    body.x += dragAmount.x / zoom
                                    body.y += dragAmount.y / zoom
                                    physics.alpha = max(physics.alpha, 0.3f)
                                } else {
                                    pan += dragAmount
                                }
                            },
                            onDragEnd = { draggedKey = null },
                            onDragCancel = { draggedKey = null },
                        )
                    }
                    .pointerInput(physics) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val zoomChange = event.calculateZoom()
                                    if (centroid != Offset.Unspecified && zoomChange > 0f) {
                                        val viewport = Size(size.width.toFloat(), size.height.toFloat())
                                        val oldZoom = zoom
                                        val newZoom = (oldZoom * zoomChange).coerceIn(0.35f, 3.5f)
                                        val worldAtCentroid = screenToWorld(centroid, viewport, oldZoom, pan)
                                        zoom = newZoom
                                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                        pan = centroid - center - (worldAtCentroid - center) * newZoom
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    },
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                withTransform({
                    translate(center.x + pan.x, center.y + pan.y)
                    scale(zoom, zoom, pivot = Offset.Zero)
                    translate(-center.x, -center.y)
                }) {
                    val bodyByKey = physics.bodies.associateBy { it.key }
                    for (edge in physics.edges) {
                        val a = bodyByKey[edge.fromKey] ?: continue
                        val b = bodyByKey[edge.toKey] ?: continue
                        drawLine(
                            color = DreamColors.Edge,
                            start = Offset(a.x, a.y),
                            end = Offset(b.x, b.y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    val nodeRadius = 5.dp.toPx()
                    val labelGap = 8.dp.toPx()
                    val maxLabelWidth = (135.dp.toPx() - 8.dp.toPx()).toInt().coerceAtLeast(1)
                    for (body in physics.bodies) {
                        drawCircle(
                            color = if (body.key == selectedKey) {
                                DreamColors.NodeSelected
                            } else {
                                DreamColors.NodeDefault
                            },
                            radius = nodeRadius,
                            center = Offset(body.x, body.y),
                        )

                        val layout = labelCache.getOrPut(body.key + ":" + body.label) {
                            textMeasurer.measure(
                                text = AnnotatedString(body.label),
                                style = TextStyle(
                                    color = DreamColors.GraphLabel,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                ),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                constraints = androidx.compose.ui.unit.Constraints(
                                    maxWidth = maxLabelWidth,
                                ),
                            )
                        }
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                x = body.x - layout.size.width / 2f,
                                y = body.y + nodeRadius + labelGap,
                            ),
                        )
                    }
                }
            }

            if (graph.nodes.isEmpty()) {
                Text(
                    text = "No nodes yet.",
                    color = DreamColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    if (sheetConcept != null) {
        ModalBottomSheet(
            onDismissRequest = {
                sheetConceptId = null
                selectedKey = null
            },
            containerColor = DreamColors.Surface,
            contentColor = DreamColors.TextPrimary,
            scrimColor = Color.Black.copy(alpha = 0.72f),
            shape = RectangleShape,
            tonalElevation = 0.dp,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(28.dp)
                        .height(1.dp)
                        .background(DreamColors.Border),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "[[${sheetConcept.label}]]",
                    color = DreamColors.TextPrimary,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${sheetNotes.size} linked note(s)",
                    color = DreamColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Hairline()
                if (sheetNotes.isEmpty()) {
                    Text(
                        text = "No notes are linked to this concept.",
                        color = DreamColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                } else {
                    sheetNotes.forEach { noteNode ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .pointerInput(noteNode.key) {
                                    detectTapGestures {
                                        sheetConceptId = null
                                        noteNode.noteId?.let(onOpenNote)
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = noteNode.label,
                                color = DreamColors.TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        Hairline()
                    }
                }
            }
        }
    }
}

private fun screenToWorld(
    screen: Offset,
    viewport: Size,
    zoom: Float,
    pan: Offset,
): Offset {
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    return center + (screen - center - pan) / zoom
}

private class PhysicsEdge(val fromKey: String, val toKey: String)

private class PhysicsBody(
    val key: String,
    val label: String,
    val kind: GraphNodeKind,
    val noteId: Long?,
    val conceptId: Long?,
) {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var vx by mutableFloatStateOf(0f)
    var vy by mutableFloatStateOf(0f)
}

private class GraphPhysics {
    val bodies = mutableStateListOf<PhysicsBody>()
    val edges = mutableStateListOf<PhysicsEdge>()
    var alpha by mutableFloatStateOf(1f)

    fun load(model: GraphModel, width: Float, height: Float, density: Float) {
        bodies.clear()
        edges.clear()
        val centerX = width / 2f
        val centerY = height / 2f
        val goldenAngle = 2.39996323f
        model.nodes.forEachIndexed { index, node ->
            val body = PhysicsBody(
                key = node.key,
                label = node.label,
                kind = node.kind,
                noteId = node.noteId,
                conceptId = node.conceptId,
            )
            // Mandatory golden-angle seeding. This formula uses raw graph units; the force
            // parameters and collision clearance remain density-scaled below.
            val radius = 140f * sqrt(index.toFloat())
            val angle = index * goldenAngle
            body.x = centerX + cos(angle) * radius
            body.y = centerY + sin(angle) * radius
            bodies += body
        }
        model.edges.forEach { edge ->
            edges += PhysicsEdge(edge.fromKey, edge.toKey)
        }
        // The initial spiral can put labels inside each other's bounding boxes. Enforce the same
        // hard clearance before the very first frame so overlap is never drawn.
        separate(density)
        alpha = 1f
    }

    fun nearest(point: Offset, radius: Float): PhysicsBody? =
        bodies
            .map { it to (it.x - point.x) * (it.x - point.x) + (it.y - point.y) * (it.y - point.y) }
            .filter { (_, distanceSq) -> distanceSq <= radius * radius }
            .minByOrNull { (_, distanceSq) -> distanceSq }
            ?.first

    fun step(width: Float, height: Float, density: Float) {
        if (bodies.isEmpty()) {
            alpha = 0f
            return
        }

        val stepAlpha = alpha
        val centerX = width / 2f
        val centerY = height / 2f
        val restLength = 190f * density
        val springK = 0.035f
        val repulsionStrength = 32_000f
        val gravity = 0.008f
        val damping = 0.72f

        val forceX = FloatArray(bodies.size)
        val forceY = FloatArray(bodies.size)

        for (i in bodies.indices) {
            forceX[i] += (centerX - bodies[i].x) * gravity
            forceY[i] += (centerY - bodies[i].y) * gravity
        }

        for (i in bodies.indices) {
            for (j in i + 1 until bodies.size) {
                val a = bodies[i]
                val b = bodies[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                var distanceSq = dx * dx + dy * dy
                if (distanceSq < 1f) {
                    val angle = (i * 37 + j * 71).toFloat()
                    dx = cos(angle) * 2f
                    dy = sin(angle) * 2f
                    distanceSq = dx * dx + dy * dy
                }
                val distance = sqrt(distanceSq).coerceAtLeast(0.01f)
                val force = repulsionStrength / distanceSq
                val fx = dx / distance * force
                val fy = dy / distance * force
                forceX[i] -= fx
                forceY[i] -= fy
                forceX[j] += fx
                forceY[j] += fy
            }
        }

        val indexByKey = bodies.withIndex().associate { (index, body) -> body.key to index }
        for (edge in edges) {
            val aIndex = indexByKey[edge.fromKey] ?: continue
            val bIndex = indexByKey[edge.toKey] ?: continue
            val a = bodies[aIndex]
            val b = bodies[bIndex]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val displacement = distance - restLength
            val force = springK * displacement
            val fx = dx / distance * force
            val fy = dy / distance * force
            forceX[aIndex] += fx
            forceY[aIndex] += fy
            forceX[bIndex] -= fx
            forceY[bIndex] -= fy
        }

        for (index in bodies.indices) {
            val body = bodies[index]
            body.vx = (body.vx + forceX[index] * stepAlpha) * damping
            body.vy = (body.vy + forceY[index] * stepAlpha) * damping
            body.x += body.vx
            body.y += body.vy
        }

        // Hard bounding-box separation. Labels extend horizontally, so radial repulsion alone
        // cannot guarantee readable space. Three pairwise passes enforce the full clearance.
        separate(density)

        alpha = (alpha - (1f / 180f)).coerceAtLeast(0f)
    }

    private fun separate(density: Float) {
        val minSepX = 135f * density
        val minSepY = 70f * density
        repeat(3) {
            for (i in bodies.indices) {
                for (j in i + 1 until bodies.size) {
                    val a = bodies[i]
                    val b = bodies[j]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val overlapX = minSepX - abs(dx)
                    val overlapY = minSepY - abs(dy)
                    if (overlapX > 0f && overlapY > 0f) {
                        val sx = if (dx == 0f) {
                            if ((i + j) % 2 == 0) 1f else -1f
                        } else {
                            sign(dx)
                        }
                        val sy = if (dy == 0f) {
                            if ((i + j) % 3 == 0) 1f else -1f
                        } else {
                            sign(dy)
                        }
                        val pushX = overlapX * 0.5f
                        val pushY = overlapY * 0.5f
                        a.x -= sx * pushX
                        a.y -= sy * pushY
                        b.x += sx * pushX
                        b.y += sy * pushY
                    }
                }
            }
        }
    }
}

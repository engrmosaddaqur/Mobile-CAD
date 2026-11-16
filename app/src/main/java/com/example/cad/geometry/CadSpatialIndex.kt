package com.example.cad.geometry

import com.example.cad.model.Bounds2D
import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Primitive-backed growable Int buffer to avoid boxing [Integer] allocations during 60fps frustum queries.
 */
class IntArrayList(initialCapacity: Int = 256) {
    var data: IntArray = IntArray(max(16, initialCapacity))
        private set
    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(value: Int) {
        if (size >= data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]
}

/**
 * High-performance 2D spatial acceleration grid with precomputed entity bounds, total drawing extents,
 * and pre-exploded [CadBlockReference] geometry for smooth 60fps pan/zoom/snap/hit-test on 50k+ entity files.
 */
class CadSpatialIndex private constructor(
    val entityCount: Int,
    private val boundsArray: Array<Bounds2D>,
    val totalBounds: Bounds2D,
    private val explodedBlocks: Map<String, List<CadEntity>>,
    val unboundedIndices: IntArray,
    val selectedIndices: IntArray,
    private val largeIndices: IntArray,
    private val gridMinX: Double,
    private val gridMinY: Double,
    private val invCellW: Double,
    private val invCellH: Double,
    private val cols: Int,
    private val rows: Int,
    private val cellHeads: IntArray,
    private val nodeEntityIdx: IntArray,
    private val nodeNext: IntArray,
    private val gridBuilt: Boolean
) {

    fun boundsAt(index: Int): Bounds2D = boundsArray[index]

    fun explodedBlockEntities(refId: String): List<CadEntity>? = explodedBlocks[refId]

    /**
     * Collects indices of all entities whose bounding box intersects [queryBounds] into [out]
     * without allocating Sets or boxed Integers. Order is NOT guaranteed; caller can sort if needed.
     */
    fun queryBounds(queryBounds: Bounds2D, out: IntArrayList, seenStamp: IntArray, stampVal: Int) {
        out.clear()
        if (entityCount == 0) return

        // Always include unbounded rays/xlines
        for (idx in unboundedIndices) {
            seenStamp[idx] = stampVal
            out.add(idx)
        }

        if (!gridBuilt) {
            for (i in 0 until entityCount) {
                if (seenStamp[i] != stampVal && boundsArray[i].intersects(queryBounds)) {
                    seenStamp[i] = stampVal
                    out.add(i)
                }
            }
            return
        }

        // Large/spanning entities stored separately so they don't bloat every cell
        for (idx in largeIndices) {
            if (seenStamp[idx] != stampVal && boundsArray[idx].intersects(queryBounds)) {
                seenStamp[idx] = stampVal
                out.add(idx)
            }
        }

        val c0 = floor((queryBounds.minX - gridMinX) * invCellW).toInt().coerceIn(0, cols - 1)
        val c1 = floor((queryBounds.maxX - gridMinX) * invCellW).toInt().coerceIn(0, cols - 1)
        val r0 = floor((queryBounds.minY - gridMinY) * invCellH).toInt().coerceIn(0, rows - 1)
        val r1 = floor((queryBounds.maxY - gridMinY) * invCellH).toInt().coerceIn(0, rows - 1)

        for (r in r0..r1) {
            val rowOff = r * cols
            for (c in c0..c1) {
                var node = cellHeads[rowOff + c]
                while (node != -1) {
                    val idx = nodeEntityIdx[node]
                    if (seenStamp[idx] != stampVal) {
                        seenStamp[idx] = stampVal
                        if (boundsArray[idx].intersects(queryBounds)) {
                            out.add(idx)
                        }
                    }
                    node = nodeNext[node]
                }
            }
        }
    }

    fun newStampBuffer(): IntArray = IntArray(max(1, entityCount))

    companion object {
        private const val GRID_THRESHOLD = 64

        fun build(
            entities: List<CadEntity>,
            blocks: Map<String, CadBlockDefinition>
        ): CadSpatialIndex {
            val n = entities.size
            if (n == 0) {
                return CadSpatialIndex(
                    entityCount = 0,
                    boundsArray = emptyArray(),
                    totalBounds = Bounds2D(-60.0, -40.0, 60.0, 40.0),
                    explodedBlocks = emptyMap(),
                    unboundedIndices = IntArray(0),
                    selectedIndices = IntArray(0),
                    largeIndices = IntArray(0),
                    gridMinX = -60.0, gridMinY = -40.0, invCellW = 1.0, invCellH = 1.0,
                    cols = 0, rows = 0, cellHeads = IntArray(0),
                    nodeEntityIdx = IntArray(0), nodeNext = IntArray(0), gridBuilt = false
                )
            }

            val explodedMap = if (blocks.isNotEmpty()) HashMap<String, List<CadEntity>>() else emptyMap()
            val unboundedTmp = IntArrayList(16)
            val selectedTmp = IntArrayList(32)

            var tMinX = Double.POSITIVE_INFINITY
            var tMinY = Double.POSITIVE_INFINITY
            var tMaxX = Double.NEGATIVE_INFINITY
            var tMaxY = Double.NEGATIVE_INFINITY

            val boundsArr = Array(n) { i ->
                val e = entities[i]
                if (e.isSelected) selectedTmp.add(i)
                if (e is CadRay || e is CadXLine) unboundedTmp.add(i)
                val b = if (e is CadBlockReference && blocks.isNotEmpty()) {
                    val def = blocks[e.blockName]
                    if (def != null && def.entities.isNotEmpty()) {
                        val children = GeometryEngine.explodeBlockReference(e, def)
                        (explodedMap as MutableMap<String, List<CadEntity>>)[e.id] = children
                        var acc = fastBoundsOf(children[0], blocks)
                        for (k in 1 until children.size) {
                            acc = acc.union(fastBoundsOf(children[k], blocks))
                        }
                        acc
                    } else {
                        fastBoundsOf(e, blocks)
                    }
                } else {
                    fastBoundsOf(e, blocks)
                }
                if (b.minX < tMinX) tMinX = b.minX
                if (b.minY < tMinY) tMinY = b.minY
                if (b.maxX > tMaxX) tMaxX = b.maxX
                if (b.maxY > tMaxY) tMaxY = b.maxY
                b
            }

            val totalBounds = if (tMinX.isFinite() && tMaxX.isFinite()) {
                Bounds2D(tMinX, tMinY, tMaxX, tMaxY)
            } else {
                Bounds2D(-60.0, -40.0, 60.0, 40.0)
            }

            val unboundedArr = unboundedTmp.data.copyOf(unboundedTmp.size)
            val selectedArr = selectedTmp.data.copyOf(selectedTmp.size)

            if (n < GRID_THRESHOLD) {
                return CadSpatialIndex(
                    entityCount = n,
                    boundsArray = boundsArr,
                    totalBounds = totalBounds,
                    explodedBlocks = explodedMap,
                    unboundedIndices = unboundedArr,
                    selectedIndices = selectedArr,
                    largeIndices = IntArray(0),
                    gridMinX = totalBounds.minX, gridMinY = totalBounds.minY,
                    invCellW = 1.0, invCellH = 1.0, cols = 0, rows = 0,
                    cellHeads = IntArray(0), nodeEntityIdx = IntArray(0), nodeNext = IntArray(0),
                    gridBuilt = false
                )
            }

            val dim = ceil(sqrt(n / 4.0)).toInt().coerceIn(8, 96)
            val cols = dim
            val rows = dim
            val spanW = max(1e-3, totalBounds.width)
            val spanH = max(1e-3, totalBounds.height)
            val invCellW = cols / spanW
            val invCellH = rows / spanH
            val gMinX = totalBounds.minX
            val gMinY = totalBounds.minY

            val cellHeads = IntArray(cols * rows) { -1 }
            val largeTmp = IntArrayList(32)
            val maxCellsPerEntity = max(16, (cols * rows) / 4)

            // First count nodes needed for compact primitive allocation
            var estNodes = n * 2
            var nodeEntity = IntArray(estNodes)
            var nodeNext = IntArray(estNodes)
            var nodeCount = 0

            for (i in 0 until n) {
                val e = entities[i]
                if (e is CadRay || e is CadXLine) continue
                val b = boundsArr[i]
                val c0 = floor((b.minX - gMinX) * invCellW).toInt().coerceIn(0, cols - 1)
                val c1 = floor((b.maxX - gMinX) * invCellW).toInt().coerceIn(0, cols - 1)
                val r0 = floor((b.minY - gMinY) * invCellH).toInt().coerceIn(0, rows - 1)
                val r1 = floor((b.maxY - gMinY) * invCellH).toInt().coerceIn(0, rows - 1)
                val cellCount = (c1 - c0 + 1) * (r1 - r0 + 1)
                if (cellCount > maxCellsPerEntity) {
                    largeTmp.add(i)
                    continue
                }
                for (r in r0..r1) {
                    val rowOff = r * cols
                    for (c in c0..c1) {
                        if (nodeCount >= estNodes) {
                            estNodes *= 2
                            nodeEntity = nodeEntity.copyOf(estNodes)
                            nodeNext = nodeNext.copyOf(estNodes)
                        }
                        val cellIdx = rowOff + c
                        nodeEntity[nodeCount] = i
                        nodeNext[nodeCount] = cellHeads[cellIdx]
                        cellHeads[cellIdx] = nodeCount
                        nodeCount++
                    }
                }
            }

            return CadSpatialIndex(
                entityCount = n,
                boundsArray = boundsArr,
                totalBounds = totalBounds,
                explodedBlocks = explodedMap,
                unboundedIndices = unboundedArr,
                selectedIndices = selectedArr,
                largeIndices = largeTmp.data.copyOf(largeTmp.size),
                gridMinX = gMinX,
                gridMinY = gMinY,
                invCellW = invCellW,
                invCellH = invCellH,
                cols = cols,
                rows = rows,
                cellHeads = cellHeads,
                nodeEntityIdx = if (nodeCount == estNodes) nodeEntity else nodeEntity.copyOf(nodeCount),
                nodeNext = if (nodeCount == estNodes) nodeNext else nodeNext.copyOf(nodeCount),
                gridBuilt = true
            )
        }

        private fun fastBoundsOf(entity: CadEntity, blocks: Map<String, CadBlockDefinition>): Bounds2D {
            return when (entity) {
                is CadLine -> Bounds2D(
                    min(entity.start.x, entity.end.x),
                    min(entity.start.y, entity.end.y),
                    max(entity.start.x, entity.end.x),
                    max(entity.start.y, entity.end.y)
                )
                is CadCircle -> Bounds2D(
                    entity.center.x - entity.radius,
                    entity.center.y - entity.radius,
                    entity.center.x + entity.radius,
                    entity.center.y + entity.radius
                )
                is CadArc -> Bounds2D(
                    entity.center.x - entity.radius,
                    entity.center.y - entity.radius,
                    entity.center.x + entity.radius,
                    entity.center.y + entity.radius
                )
                is CadEllipse -> {
                    val r = max(entity.majorAxisEndOffset.length(), 1e-4)
                    Bounds2D(entity.center.x - r, entity.center.y - r, entity.center.x + r, entity.center.y + r)
                }
                is CadPolyline -> {
                    var minX = Double.POSITIVE_INFINITY
                    var minY = Double.POSITIVE_INFINITY
                    var maxX = Double.NEGATIVE_INFINITY
                    var maxY = Double.NEGATIVE_INFINITY
                    for (pt in entity.points) {
                        if (pt.x < minX) minX = pt.x
                        if (pt.y < minY) minY = pt.y
                        if (pt.x > maxX) maxX = pt.x
                        if (pt.y > maxY) maxY = pt.y
                    }
                    if (minX.isFinite()) Bounds2D(minX, minY, maxX, maxY) else Bounds2D(0.0, 0.0, 0.0, 0.0)
                }
                is CadSpline -> {
                    var minX = Double.POSITIVE_INFINITY
                    var minY = Double.POSITIVE_INFINITY
                    var maxX = Double.NEGATIVE_INFINITY
                    var maxY = Double.NEGATIVE_INFINITY
                    for (pt in entity.controlPoints) {
                        if (pt.x < minX) minX = pt.x
                        if (pt.y < minY) minY = pt.y
                        if (pt.x > maxX) maxX = pt.x
                        if (pt.y > maxY) maxY = pt.y
                    }
                    if (minX.isFinite()) Bounds2D(minX, minY, maxX, maxY) else Bounds2D(0.0, 0.0, 0.0, 0.0)
                }
                else -> GeometryEngine.boundsOf(entity, blocks)
            }
        }
    }
}

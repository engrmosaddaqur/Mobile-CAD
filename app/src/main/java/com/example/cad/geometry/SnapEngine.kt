package com.example.cad.geometry

import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.OsnapType
import com.example.cad.model.SnapSettings
import com.example.cad.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

data class SnapResult(
    val point: Vec2,
    val osnapType: OsnapType? = null,
    val trackingGuide: Pair<Vec2, Vec2>? = null,
    val label: String? = null
)

object SnapEngine {

    fun resolveSnap(
        rawWorldPoint: Vec2,
        anchorPoint: Vec2?,
        entities: List<CadEntity>,
        blocks: Map<String, CadBlockDefinition>,
        visibleLayerNames: Set<String>,
        snapSettings: SnapSettings,
        snapToleranceWorld: Double,
        spatialIndex: CadSpatialIndex? = null
    ): SnapResult {
        var bestPoint = rawWorldPoint
        var bestOsnap: OsnapType? = null
        var bestDist = snapToleranceWorld
        var trackingGuide: Pair<Vec2, Vec2>? = null

        val queryRadius = snapToleranceWorld * 3.5
        val visibleNearbyEntities: List<CadEntity> = if (spatialIndex != null && spatialIndex.entityCount == entities.size) {
            val box = com.example.cad.model.Bounds2D(
                rawWorldPoint.x - queryRadius,
                rawWorldPoint.y - queryRadius,
                rawWorldPoint.x + queryRadius,
                rawWorldPoint.y + queryRadius
            )
            val hits = IntArrayList(64)
            val stamp = spatialIndex.newStampBuffer()
            spatialIndex.queryBounds(box, hits, stamp, 1)
            val out = ArrayList<CadEntity>(hits.size)
            for (k in 0 until hits.size) {
                val e = entities[hits[k]]
                if (e.layerName in visibleLayerNames) out.add(e)
            }
            out
        } else {
            entities.filter { it.layerName in visibleLayerNames }
        }

        // 1. Apply OSNAP candidates
        if (snapSettings.osnapEnabled && snapSettings.enabledOsnaps.isNotEmpty()) {
            val candidates = mutableListOf<Pair<OsnapType, Vec2>>()

            for (entity in visibleNearbyEntities) {
                if (GeometryEngine.distanceToEntity(rawWorldPoint, entity, blocks) > queryRadius) {
                    continue
                }
                collectEntityOsnaps(entity, anchorPoint, rawWorldPoint, snapSettings.enabledOsnaps, blocks, candidates)
            }

            if (OsnapType.INTERSECTION in snapSettings.enabledOsnaps) {
                collectIntersections(rawWorldPoint, visibleNearbyEntities, blocks, snapToleranceWorld * 2.5, candidates)
            }

            for ((type, pt) in candidates) {
                if (type == OsnapType.NEAREST) continue
                val d = rawWorldPoint.distanceTo(pt)
                if (d < bestDist) {
                    bestDist = d
                    bestPoint = pt
                    bestOsnap = type
                }
            }
            if (bestOsnap == null) {
                for ((type, pt) in candidates) {
                    if (type != OsnapType.NEAREST) continue
                    val d = rawWorldPoint.distanceTo(pt)
                    if (d < bestDist) {
                        bestDist = d
                        bestPoint = pt
                        bestOsnap = type
                    }
                }
            }
        }

        // 2. If no OSNAP locked, apply Ortho / Polar Tracking / Extension / Grid Snap
        if (bestOsnap == null) {
            var constrained = rawWorldPoint

            if (anchorPoint != null) {
                if (snapSettings.orthoMode) {
                    val dx = abs(rawWorldPoint.x - anchorPoint.x)
                    val dy = abs(rawWorldPoint.y - anchorPoint.y)
                    constrained = if (dx >= dy) {
                        Vec2(rawWorldPoint.x, anchorPoint.y)
                    } else {
                        Vec2(anchorPoint.x, rawWorldPoint.y)
                    }
                    trackingGuide = anchorPoint to constrained
                } else if (snapSettings.polarTracking && snapSettings.polarIncrementDeg > 0.0) {
                    val v = rawWorldPoint - anchorPoint
                    val dist = v.length()
                    if (dist > 1e-4) {
                        val angleDeg = v.angleDeg()
                        val inc = snapSettings.polarIncrementDeg
                        val nearestPolar = round(angleDeg / inc) * inc
                        if (abs(angleDeg - nearestPolar) <= 5.5) {
                            constrained = Vec2.fromPolar(anchorPoint, dist, nearestPolar)
                            val farGuide = Vec2.fromPolar(anchorPoint, dist + 200.0, nearestPolar)
                            trackingGuide = anchorPoint to farGuide
                        }
                    }
                }
            }

            // Extension snap along nearby line directions
            if (snapSettings.osnapEnabled && OsnapType.EXTENSION in snapSettings.enabledOsnaps && trackingGuide == null) {
                for (entity in visibleNearbyEntities) {
                    if (entity is CadLine) {
                        val dir = (entity.end - entity.start).normalized()
                        val (d, proj) = GeometryEngine.pointToInfiniteLineDistance(constrained, entity.start, dir, rayOnly = false)
                        if (d < snapToleranceWorld * 0.55) {
                            constrained = proj
                            bestOsnap = OsnapType.EXTENSION
                            val fromEnd = if (proj.distanceTo(entity.start) < proj.distanceTo(entity.end)) entity.start else entity.end
                            trackingGuide = fromEnd to proj
                            break
                        }
                    }
                }
            }

            if (snapSettings.gridSnapEnabled && snapSettings.gridSpacing > 1e-6 && bestOsnap == null) {
                val step = snapSettings.gridSpacing
                constrained = Vec2(
                    round(constrained.x / step) * step,
                    round(constrained.y / step) * step
                )
            }

            bestPoint = constrained
        }

        return SnapResult(
            point = bestPoint,
            osnapType = bestOsnap,
            trackingGuide = trackingGuide,
            label = bestOsnap?.shortCode
        )
    }

    private fun collectEntityOsnaps(
        entity: CadEntity,
        anchor: Vec2?,
        cursor: Vec2,
        enabled: Set<OsnapType>,
        blocks: Map<String, CadBlockDefinition>,
        out: MutableList<Pair<OsnapType, Vec2>>
    ) {
        when (entity) {
            is CadLine -> {
                if (OsnapType.ENDPOINT in enabled) {
                    out.add(OsnapType.ENDPOINT to entity.start)
                    out.add(OsnapType.ENDPOINT to entity.end)
                }
                if (OsnapType.MIDPOINT in enabled) {
                    out.add(OsnapType.MIDPOINT to (entity.start + entity.end) * 0.5)
                }
                if (OsnapType.PERPENDICULAR in enabled && anchor != null) {
                    val ab = entity.end - entity.start
                    val lenSq = ab.lengthSquared()
                    if (lenSq > 1e-9) {
                        val t = (anchor - entity.start).dot(ab) / lenSq
                        if (t in 0.0..1.0) {
                            out.add(OsnapType.PERPENDICULAR to (entity.start + ab * t))
                        }
                    }
                }
                if (OsnapType.NEAREST in enabled) {
                    out.add(OsnapType.NEAREST to GeometryEngine.pointToSegmentDistance(cursor, entity.start, entity.end).second)
                }
            }
            is CadPolyline -> {
                if (OsnapType.ENDPOINT in enabled) {
                    entity.points.forEach { out.add(OsnapType.ENDPOINT to it) }
                }
                if (OsnapType.MIDPOINT in enabled && entity.points.size >= 2) {
                    val count = if (entity.isClosed) entity.points.size else entity.points.size - 1
                    for (i in 0 until count) {
                        out.add(OsnapType.MIDPOINT to (entity.points[i] + entity.points[(i + 1) % entity.points.size]) * 0.5)
                    }
                }
                if (OsnapType.NEAREST in enabled) {
                    out.add(OsnapType.NEAREST to GeometryEngine.nearestPointOnEntity(cursor, entity, blocks).second)
                }
            }
            is CadCircle -> {
                if (OsnapType.CENTER in enabled) out.add(OsnapType.CENTER to entity.center)
                if (OsnapType.QUADRANT in enabled) {
                    out.add(OsnapType.QUADRANT to Vec2(entity.center.x + entity.radius, entity.center.y))
                    out.add(OsnapType.QUADRANT to Vec2(entity.center.x - entity.radius, entity.center.y))
                    out.add(OsnapType.QUADRANT to Vec2(entity.center.x, entity.center.y + entity.radius))
                    out.add(OsnapType.QUADRANT to Vec2(entity.center.x, entity.center.y - entity.radius))
                }
                if (OsnapType.TANGENT in enabled && anchor != null) {
                    collectCircleTangents(anchor, entity.center, entity.radius).forEach {
                        out.add(OsnapType.TANGENT to it)
                    }
                }
                if (OsnapType.PERPENDICULAR in enabled && anchor != null) {
                    val dir = (anchor - entity.center).normalized()
                    out.add(OsnapType.PERPENDICULAR to (entity.center + dir * entity.radius))
                    out.add(OsnapType.PERPENDICULAR to (entity.center - dir * entity.radius))
                }
                if (OsnapType.NEAREST in enabled) {
                    out.add(OsnapType.NEAREST to GeometryEngine.nearestPointOnEntity(cursor, entity, blocks).second)
                }
            }
            is CadArc -> {
                if (OsnapType.CENTER in enabled) out.add(OsnapType.CENTER to entity.center)
                if (OsnapType.ENDPOINT in enabled) {
                    out.add(OsnapType.ENDPOINT to Vec2.fromPolar(entity.center, entity.radius, entity.startAngleDeg))
                    out.add(OsnapType.ENDPOINT to Vec2.fromPolar(entity.center, entity.radius, entity.endAngleDeg))
                }
                if (OsnapType.MIDPOINT in enabled) {
                    val sweep = GeometryEngine.normalizeSweepDeg(entity.startAngleDeg, entity.endAngleDeg)
                    out.add(OsnapType.MIDPOINT to Vec2.fromPolar(entity.center, entity.radius, entity.startAngleDeg + sweep * 0.5))
                }
                if (OsnapType.TANGENT in enabled && anchor != null) {
                    collectCircleTangents(anchor, entity.center, entity.radius).forEach {
                        out.add(OsnapType.TANGENT to it)
                    }
                }
                if (OsnapType.NEAREST in enabled) {
                    out.add(OsnapType.NEAREST to GeometryEngine.nearestPointOnEntity(cursor, entity, blocks).second)
                }
            }
            is CadEllipse -> {
                if (OsnapType.CENTER in enabled) out.add(OsnapType.CENTER to entity.center)
                if (OsnapType.QUADRANT in enabled) {
                    out.add(OsnapType.QUADRANT to (entity.center + entity.majorAxisEndOffset))
                    out.add(OsnapType.QUADRANT to (entity.center - entity.majorAxisEndOffset))
                }
            }
            is CadSpline -> {
                if (OsnapType.ENDPOINT in enabled && entity.controlPoints.isNotEmpty()) {
                    out.add(OsnapType.ENDPOINT to entity.controlPoints.first())
                    out.add(OsnapType.ENDPOINT to entity.controlPoints.last())
                }
            }
            is CadPoint -> {
                if (OsnapType.ENDPOINT in enabled || OsnapType.NEAREST in enabled) {
                    out.add(OsnapType.ENDPOINT to entity.position)
                }
            }
            is CadBlockReference -> {
                if (OsnapType.ENDPOINT in enabled) out.add(OsnapType.ENDPOINT to entity.insertionPoint)
                val def = blocks[entity.blockName]
                if (def != null) {
                    GeometryEngine.explodeBlockReference(entity, def).forEach { sub ->
                        collectEntityOsnaps(sub, anchor, cursor, enabled, blocks, out)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun collectCircleTangents(from: Vec2, center: Vec2, radius: Double): List<Vec2> {
        val d = from.distanceTo(center)
        if (d <= radius + 1e-6) return emptyList()
        val baseAngle = atan2(from.y - center.y, from.x - center.x)
        val alpha = kotlin.math.acos((radius / d).coerceIn(-1.0, 1.0))
        return listOf(
            Vec2(center.x + radius * cos(baseAngle + alpha), center.y + radius * sin(baseAngle + alpha)),
            Vec2(center.x + radius * cos(baseAngle - alpha), center.y + radius * sin(baseAngle - alpha))
        )
    }

    private fun collectIntersections(
        cursor: Vec2,
        entities: List<CadEntity>,
        blocks: Map<String, CadBlockDefinition>,
        radius: Double,
        out: MutableList<Pair<OsnapType, Vec2>>
    ) {
        val nearby = entities.filter { GeometryEngine.distanceToEntity(cursor, it, blocks) <= radius }.take(16)
        for (i in nearby.indices) {
            for (j in i + 1 until nearby.size) {
                val e1 = nearby[i]
                val e2 = nearby[j]
                if (e1 is CadCircle && e2 is CadCircle) {
                    GeometryEngine.circleCircleIntersections(e1.center, e1.radius, e2.center, e2.radius)
                        .forEach { out.add(OsnapType.INTERSECTION to it) }
                } else {
                    val segs1 = GeometryEngine.entityToSegments(e1, blocks)
                    val segs2 = GeometryEngine.entityToSegments(e2, blocks)
                    for ((a1, b1) in segs1) {
                        for ((a2, b2) in segs2) {
                            GeometryEngine.segmentIntersection(a1, b1, a2, b2)?.let {
                                out.add(OsnapType.INTERSECTION to it)
                            }
                        }
                    }
                }
            }
        }
    }
}

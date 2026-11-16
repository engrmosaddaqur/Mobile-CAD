package com.example.cad.geometry

import com.example.cad.model.Bounds2D
import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object GeometryEngine {

    fun boundsOf(entity: CadEntity, blocks: Map<String, CadBlockDefinition> = emptyMap()): Bounds2D {
        return when (entity) {
            is CadLine -> Bounds2D.fromPoints(listOf(entity.start, entity.end))
            is CadPolyline -> Bounds2D.fromPoints(entity.points)
            is CadCircle -> Bounds2D(
                entity.center.x - entity.radius,
                entity.center.y - entity.radius,
                entity.center.x + entity.radius,
                entity.center.y + entity.radius
            )
            is CadArc -> Bounds2D.fromPoints(sampleArcPoints(entity, 32))
            is CadEllipse -> Bounds2D.fromPoints(sampleEllipsePoints(entity, 48))
            is CadSpline -> Bounds2D.fromPoints(sampleSplinePoints(entity, 12))
            is CadPoint -> Bounds2D(entity.position.x - 1.0, entity.position.y - 1.0, entity.position.x + 1.0, entity.position.y + 1.0)
            is CadRay -> Bounds2D.fromPoints(listOf(entity.origin, entity.origin + entity.direction.normalized() * 100.0))
            is CadXLine -> Bounds2D.fromPoints(
                listOf(
                    entity.origin - entity.direction.normalized() * 100.0,
                    entity.origin + entity.direction.normalized() * 100.0
                )
            )
            is CadText -> {
                val w = max(entity.text.length, 1) * entity.height * 0.65
                Bounds2D(entity.position.x, entity.position.y, entity.position.x + w, entity.position.y + entity.height)
            }
            is CadBlockReference -> {
                val def = blocks[entity.blockName]
                if (def == null || def.entities.isEmpty()) {
                    Bounds2D(entity.insertionPoint.x - 5.0, entity.insertionPoint.y - 5.0, entity.insertionPoint.x + 5.0, entity.insertionPoint.y + 5.0)
                } else {
                    val exploded = explodeBlockReference(entity, def)
                    var acc = boundsOf(exploded.first(), blocks)
                    for (i in 1 until exploded.size) {
                        acc = acc.union(boundsOf(exploded[i], blocks))
                    }
                    acc
                }
            }
            is CadDimension -> {
                val pts = mutableListOf(entity.p1, entity.p2, entity.dimLinePoint)
                entity.p3?.let { pts.add(it) }
                Bounds2D.fromPoints(pts)
            }
        }
    }

    fun boundsOfAll(entities: List<CadEntity>, blocks: Map<String, CadBlockDefinition> = emptyMap()): Bounds2D {
        if (entities.isEmpty()) return Bounds2D(-60.0, -40.0, 60.0, 40.0)
        var acc = boundsOf(entities.first(), blocks)
        for (i in 1 until entities.size) {
            acc = acc.union(boundsOf(entities[i], blocks))
        }
        return acc
    }

    fun sampleArcPoints(arc: CadArc, segments: Int = 36): List<Vec2> {
        val sweep = normalizeSweepDeg(arc.startAngleDeg, arc.endAngleDeg)
        val count = max(4, (segments * sweep / 360.0).toInt().coerceAtLeast(6))
        return (0..count).map { idx ->
            val deg = arc.startAngleDeg + sweep * (idx.toDouble() / count)
            val rad = Math.toRadians(deg)
            Vec2(arc.center.x + arc.radius * cos(rad), arc.center.y + arc.radius * sin(rad))
        }
    }

    fun normalizeSweepDeg(startDeg: Double, endDeg: Double): Double {
        var sweep = (endDeg - startDeg) % 360.0
        if (sweep <= 0.0) sweep += 360.0
        return sweep
    }

    fun sampleEllipsePoints(ellipse: CadEllipse, segments: Int = 48): List<Vec2> {
        val major = ellipse.majorAxisEndOffset
        val majorLen = major.length()
        if (majorLen < 1e-9) return listOf(ellipse.center)
        val rotRad = major.angleRad()
        val minorLen = majorLen * ellipse.minorToMajorRatio
        val cRot = cos(rotRad)
        val sRot = sin(rotRad)
        var sweep = ellipse.endParamRad - ellipse.startParamRad
        if (sweep <= 0.0) sweep += 2.0 * PI
        return (0..segments).map { i ->
            val t = ellipse.startParamRad + sweep * (i.toDouble() / segments)
            val lx = majorLen * cos(t)
            val ly = minorLen * sin(t)
            Vec2(
                ellipse.center.x + lx * cRot - ly * sRot,
                ellipse.center.y + lx * sRot + ly * cRot
            )
        }
    }

    fun sampleSplinePoints(spline: CadSpline, subdivisionsPerSegment: Int = 10): List<Vec2> {
        val pts = spline.controlPoints
        if (pts.size < 2) return pts
        if (pts.size == 2) return pts
        val result = mutableListOf<Vec2>()
        val n = pts.size
        val segCount = if (spline.isClosed) n else n - 1
        for (i in 0 until segCount) {
            val p0 = pts[(i - 1 + n) % n].let { if (!spline.isClosed && i == 0) pts[0] else it }
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val p3 = pts[(i + 2) % n].let { if (!spline.isClosed && i + 2 >= n) pts.last() else it }
            for (step in 0 until subdivisionsPerSegment) {
                val t = step.toDouble() / subdivisionsPerSegment
                result.add(catmullRom(p0, p1, p2, p3, t))
            }
        }
        result.add(if (spline.isClosed) pts.first() else pts.last())
        return result
    }

    private fun catmullRom(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, t: Double): Vec2 {
        val t2 = t * t
        val t3 = t2 * t
        val x = 0.5 * ((2.0 * p1.x) +
            (-p0.x + p2.x) * t +
            (2.0 * p0.x - 5.0 * p1.x + 4.0 * p2.x - p3.x) * t2 +
            (-p0.x + 3.0 * p1.x - 3.0 * p2.x + p3.x) * t3)
        val y = 0.5 * ((2.0 * p1.y) +
            (-p0.y + p2.y) * t +
            (2.0 * p0.y - 5.0 * p1.y + 4.0 * p2.y - p3.y) * t2 +
            (-p0.y + 3.0 * p1.y - 3.0 * p2.y + p3.y) * t3)
        return Vec2(x, y)
    }

    fun pointToSegmentDistance(p: Vec2, a: Vec2, b: Vec2): Pair<Double, Vec2> {
        val ab = b - a
        val lenSq = ab.lengthSquared()
        if (lenSq < 1e-12) return p.distanceTo(a) to a
        val t = ((p - a).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        val proj = a + ab * t
        return p.distanceTo(proj) to proj
    }

    fun pointToInfiniteLineDistance(p: Vec2, origin: Vec2, dir: Vec2, rayOnly: Boolean): Pair<Double, Vec2> {
        val u = dir.normalized()
        var t = (p - origin).dot(u)
        if (rayOnly && t < 0.0) t = 0.0
        val proj = origin + u * t
        return p.distanceTo(proj) to proj
    }

    fun distanceToEntity(
        p: Vec2,
        entity: CadEntity,
        blocks: Map<String, CadBlockDefinition> = emptyMap()
    ): Double = nearestPointOnEntity(p, entity, blocks).first

    fun nearestPointOnEntity(
        p: Vec2,
        entity: CadEntity,
        blocks: Map<String, CadBlockDefinition> = emptyMap()
    ): Pair<Double, Vec2> {
        return when (entity) {
            is CadLine -> pointToSegmentDistance(p, entity.start, entity.end)
            is CadPolyline -> {
                if (entity.points.isEmpty()) return Double.POSITIVE_INFINITY to p
                if (entity.points.size == 1) return p.distanceTo(entity.points[0]) to entity.points[0]
                var bestDist = Double.POSITIVE_INFINITY
                var bestPt = entity.points[0]
                val count = if (entity.isClosed) entity.points.size else entity.points.size - 1
                for (i in 0 until count) {
                    val a = entity.points[i]
                    val b = entity.points[(i + 1) % entity.points.size]
                    val (d, pt) = pointToSegmentDistance(p, a, b)
                    if (d < bestDist) {
                        bestDist = d
                        bestPt = pt
                    }
                }
                bestDist to bestPt
            }
            is CadCircle -> {
                val v = p - entity.center
                val len = v.length()
                val closest = if (len > 1e-9) {
                    entity.center + v * (entity.radius / len)
                } else {
                    Vec2(entity.center.x + entity.radius, entity.center.y)
                }
                abs(len - entity.radius) to closest
            }
            is CadArc -> {
                val pts = sampleArcPoints(entity, 48)
                var bestDist = Double.POSITIVE_INFINITY
                var bestPt = pts.firstOrNull() ?: entity.center
                for (i in 0 until pts.size - 1) {
                    val (d, pt) = pointToSegmentDistance(p, pts[i], pts[i + 1])
                    if (d < bestDist) {
                        bestDist = d
                        bestPt = pt
                    }
                }
                bestDist to bestPt
            }
            is CadEllipse -> {
                val pts = sampleEllipsePoints(entity, 64)
                var bestDist = Double.POSITIVE_INFINITY
                var bestPt = entity.center
                for (i in 0 until pts.size - 1) {
                    val (d, pt) = pointToSegmentDistance(p, pts[i], pts[i + 1])
                    if (d < bestDist) {
                        bestDist = d
                        bestPt = pt
                    }
                }
                bestDist to bestPt
            }
            is CadSpline -> {
                val pts = sampleSplinePoints(entity, 12)
                var bestDist = Double.POSITIVE_INFINITY
                var bestPt = entity.controlPoints.firstOrNull() ?: p
                for (i in 0 until pts.size - 1) {
                    val (d, pt) = pointToSegmentDistance(p, pts[i], pts[i + 1])
                    if (d < bestDist) {
                        bestDist = d
                        bestPt = pt
                    }
                }
                bestDist to bestPt
            }
            is CadPoint -> p.distanceTo(entity.position) to entity.position
            is CadRay -> pointToInfiniteLineDistance(p, entity.origin, entity.direction, rayOnly = true)
            is CadXLine -> pointToInfiniteLineDistance(p, entity.origin, entity.direction, rayOnly = false)
            is CadText -> {
                val b = boundsOf(entity)
                val cx = p.x.coerceIn(b.minX, b.maxX)
                val cy = p.y.coerceIn(b.minY, b.maxY)
                val clamped = Vec2(cx, cy)
                p.distanceTo(clamped) to clamped
            }
            is CadBlockReference -> {
                val def = blocks[entity.blockName]
                if (def == null) {
                    p.distanceTo(entity.insertionPoint) to entity.insertionPoint
                } else {
                    var bestDist = Double.POSITIVE_INFINITY
                    var bestPt = entity.insertionPoint
                    for (sub in explodeBlockReference(entity, def)) {
                        val (d, pt) = nearestPointOnEntity(p, sub, blocks)
                        if (d < bestDist) {
                            bestDist = d
                            bestPt = pt
                        }
                    }
                    bestDist to bestPt
                }
            }
            is CadDimension -> {
                val (d1, pt1) = pointToSegmentDistance(p, entity.p1, entity.dimLinePoint)
                val (d2, pt2) = pointToSegmentDistance(p, entity.p2, entity.dimLinePoint)
                if (d1 < d2) d1 to pt1 else d2 to pt2
            }
        }
    }

    fun hitTest(
        worldPoint: Vec2,
        entities: List<CadEntity>,
        toleranceWorld: Double,
        blocks: Map<String, CadBlockDefinition> = emptyMap(),
        visibleLayerNames: Set<String>? = null,
        spatialIndex: CadSpatialIndex? = null
    ): CadEntity? {
        var best: CadEntity? = null
        var bestDist = toleranceWorld

        if (spatialIndex != null && spatialIndex.entityCount == entities.size) {
            val box = Bounds2D(
                worldPoint.x - toleranceWorld,
                worldPoint.y - toleranceWorld,
                worldPoint.x + toleranceWorld,
                worldPoint.y + toleranceWorld
            )
            val candidates = IntArrayList(64)
            val stamp = spatialIndex.newStampBuffer()
            spatialIndex.queryBounds(box, candidates, stamp, 1)
            // Iterate descending index order so top-most entity wins on ties
            val arr = candidates.data
            var bestIdx = -1
            for (k in 0 until candidates.size) {
                val idx = arr[k]
                val entity = entities[idx]
                if (visibleLayerNames != null && entity.layerName !in visibleLayerNames) continue
                val d = distanceToEntity(worldPoint, entity, blocks)
                if (d < bestDist || (d == bestDist && idx > bestIdx)) {
                    bestDist = d
                    best = entity
                    bestIdx = idx
                }
            }
            return best
        }

        for (entity in entities.asReversed()) {
            if (visibleLayerNames != null && entity.layerName !in visibleLayerNames) continue
            val d = distanceToEntity(worldPoint, entity, blocks)
            if (d <= bestDist) {
                bestDist = d
                best = entity
            }
        }
        return best
    }

    fun selectByRect(
        start: Vec2,
        end: Vec2,
        entities: List<CadEntity>,
        blocks: Map<String, CadBlockDefinition> = emptyMap(),
        visibleLayerNames: Set<String>,
        spatialIndex: CadSpatialIndex? = null
    ): Set<String> {
        val rect = Bounds2D(
            min(start.x, end.x),
            min(start.y, end.y),
            max(start.x, end.x),
            max(start.y, end.y)
        )
        val isWindowSelection = end.x >= start.x // Left-to-Right = Window (strictly inside), Right-to-Left = Crossing

        if (spatialIndex != null && spatialIndex.entityCount == entities.size) {
            val candidates = IntArrayList(256)
            val stamp = spatialIndex.newStampBuffer()
            spatialIndex.queryBounds(rect, candidates, stamp, 1)
            val out = HashSet<String>(min(candidates.size, 256) + 16)
            for (k in 0 until candidates.size) {
                val idx = candidates[k]
                val entity = entities[idx]
                if (entity.layerName !in visibleLayerNames) continue
                val eb = spatialIndex.boundsAt(idx)
                val matched = if (isWindowSelection) {
                    rect.contains(eb)
                } else {
                    rect.intersects(eb) && entityIntersectsRect(entity, rect, blocks)
                }
                if (matched) out.add(entity.id)
            }
            return out
        }

        return entities.asSequence()
            .filter { it.layerName in visibleLayerNames }
            .filter { entity ->
                val eb = boundsOf(entity, blocks)
                if (isWindowSelection) {
                    rect.contains(eb)
                } else {
                    rect.intersects(eb) && entityIntersectsRect(entity, rect, blocks)
                }
            }
            .map { it.id }
            .toSet()
    }

    private fun entityIntersectsRect(
        entity: CadEntity,
        rect: Bounds2D,
        blocks: Map<String, CadBlockDefinition>
    ): Boolean {
        val segments = entityToSegments(entity, blocks)
        if (segments.isEmpty()) return rect.intersects(boundsOf(entity, blocks))
        for ((a, b) in segments) {
            if (rect.containsPoint(a) || rect.containsPoint(b)) return true
            val edges = listOf(
                Vec2(rect.minX, rect.minY) to Vec2(rect.maxX, rect.minY),
                Vec2(rect.maxX, rect.minY) to Vec2(rect.maxX, rect.maxY),
                Vec2(rect.maxX, rect.maxY) to Vec2(rect.minX, rect.maxY),
                Vec2(rect.minX, rect.maxY) to Vec2(rect.minX, rect.minY)
            )
            for ((e1, e2) in edges) {
                if (segmentIntersection(a, b, e1, e2) != null) return true
            }
        }
        return false
    }

    fun entityToSegments(
        entity: CadEntity,
        blocks: Map<String, CadBlockDefinition> = emptyMap()
    ): List<Pair<Vec2, Vec2>> {
        return when (entity) {
            is CadLine -> listOf(entity.start to entity.end)
            is CadPolyline -> {
                if (entity.points.size < 2) emptyList()
                else {
                    val count = if (entity.isClosed) entity.points.size else entity.points.size - 1
                    (0 until count).map { i -> entity.points[i] to entity.points[(i + 1) % entity.points.size] }
                }
            }
            is CadCircle -> {
                val n = 48
                val pts = (0..n).map { i ->
                    val a = 2.0 * PI * i / n
                    Vec2(entity.center.x + entity.radius * cos(a), entity.center.y + entity.radius * sin(a))
                }
                pts.zipWithNext()
            }
            is CadArc -> sampleArcPoints(entity, 36).zipWithNext()
            is CadEllipse -> sampleEllipsePoints(entity, 48).zipWithNext()
            is CadSpline -> sampleSplinePoints(entity, 10).zipWithNext()
            is CadRay -> listOf(entity.origin to (entity.origin + entity.direction.normalized() * 10000.0))
            is CadXLine -> listOf(
                (entity.origin - entity.direction.normalized() * 10000.0) to
                    (entity.origin + entity.direction.normalized() * 10000.0)
            )
            is CadBlockReference -> {
                val def = blocks[entity.blockName] ?: return emptyList()
                explodeBlockReference(entity, def).flatMap { entityToSegments(it, blocks) }
            }
            else -> emptyList()
        }
    }

    fun segmentIntersection(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Vec2? {
        val d1 = p2 - p1
        val d2 = p4 - p3
        val denom = d1.cross(d2)
        if (abs(denom) < 1e-10) return null
        val diff = p3 - p1
        val t = diff.cross(d2) / denom
        val u = diff.cross(d1) / denom
        return if (t in -1e-7..1.0 + 1e-7 && u in -1e-7..1.0 + 1e-7) {
            p1 + d1 * t.coerceIn(0.0, 1.0)
        } else null
    }

    fun lineIntersection(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Vec2? {
        val d1 = p2 - p1
        val d2 = p4 - p3
        val denom = d1.cross(d2)
        if (abs(denom) < 1e-10) return null
        val t = (p3 - p1).cross(d2) / denom
        return p1 + d1 * t
    }

    fun lineCircleIntersections(a: Vec2, b: Vec2, center: Vec2, radius: Double, segmentOnly: Boolean): List<Vec2> {
        val d = b - a
        val f = a - center
        val aa = d.dot(d)
        if (aa < 1e-12) return emptyList()
        val bb = 2.0 * f.dot(d)
        val cc = f.dot(f) - radius * radius
        val disc = bb * bb - 4.0 * aa * cc
        if (disc < -1e-9) return emptyList()
        val sqrtDisc = kotlin.math.sqrt(max(0.0, disc))
        val ts = listOf((-bb - sqrtDisc) / (2.0 * aa), (-bb + sqrtDisc) / (2.0 * aa)).distinct()
        return ts.filter { !segmentOnly || it in -1e-7..1.0 + 1e-7 }
            .map { a + d * (if (segmentOnly) it.coerceIn(0.0, 1.0) else it) }
    }

    fun circleCircleIntersections(c1: Vec2, r1: Double, c2: Vec2, r2: Double): List<Vec2> {
        val d = c1.distanceTo(c2)
        if (d < 1e-9 || d > r1 + r2 + 1e-9 || d < abs(r1 - r2) - 1e-9) return emptyList()
        val a = (r1 * r1 - r2 * r2 + d * d) / (2.0 * d)
        val hSq = max(0.0, r1 * r1 - a * a)
        val h = kotlin.math.sqrt(hSq)
        val p2 = c1 + (c2 - c1) * (a / d)
        val rx = -(c2.y - c1.y) * (h / d)
        val ry = (c2.x - c1.x) * (h / d)
        val pA = Vec2(p2.x + rx, p2.y + ry)
        val pB = Vec2(p2.x - rx, p2.y - ry)
        return if (pA.distanceTo(pB) < 1e-6) listOf(pA) else listOf(pA, pB)
    }

    fun computeDimensionValue(dim: CadDimension, scaleFactor: Double = 1.0): Double {
        return when (dim.dimType) {
            DimensionType.LINEAR, DimensionType.CONTINUE, DimensionType.BASELINE -> {
                val dx = abs(dim.p2.x - dim.p1.x)
                val dy = abs(dim.p2.y - dim.p1.y)
                val isHorizontal = abs(dim.dimLinePoint.y - (dim.p1.y + dim.p2.y) * 0.5) >=
                    abs(dim.dimLinePoint.x - (dim.p1.x + dim.p2.x) * 0.5)
                (if (isHorizontal) dx else dy) * scaleFactor
            }
            DimensionType.ALIGNED -> dim.p1.distanceTo(dim.p2) * scaleFactor
            DimensionType.ANGULAR -> {
                val vertex = dim.p1
                val v1 = (dim.p2 - vertex).normalized()
                val v2 = ((dim.p3 ?: dim.dimLinePoint) - vertex).normalized()
                Math.toDegrees(acos(v1.dot(v2).coerceIn(-1.0, 1.0)))
            }
            DimensionType.RADIUS -> dim.p1.distanceTo(dim.p2) * scaleFactor
            DimensionType.DIAMETER -> dim.p1.distanceTo(dim.p2) * 2.0 * scaleFactor
            DimensionType.ARC_LENGTH -> {
                val r = dim.p1.distanceTo(dim.p2)
                val a1 = (dim.p2 - dim.p1).angleDeg()
                val a2 = ((dim.p3 ?: dim.p2) - dim.p1).angleDeg()
                val sweep = normalizeSweepDeg(a1, a2)
                r * Math.toRadians(sweep) * scaleFactor
            }
            DimensionType.LEADER, DimensionType.MULTILEADER -> dim.p1.distanceTo(dim.p2) * scaleFactor
        }
    }

    fun polygonAreaAndPerimeter(points: List<Vec2>, closed: Boolean = true): Pair<Double, Double> {
        if (points.size < 2) return 0.0 to 0.0
        var perim = 0.0
        val segCount = if (closed) points.size else points.size - 1
        for (i in 0 until segCount) {
            perim += points[i].distanceTo(points[(i + 1) % points.size])
        }
        if (points.size < 3) return 0.0 to perim
        var area2 = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            area2 += a.x * b.y - b.x * a.y
        }
        return abs(area2) * 0.5 to perim
    }

    fun explodeBlockReference(ref: CadBlockReference, def: CadBlockDefinition): List<CadEntity> {
        val rad = Math.toRadians(ref.rotationDeg)
        return def.entities.map { sub ->
            transformBlockChild(sub, def.basePoint, ref.insertionPoint, ref.scaleX, ref.scaleY, rad, ref.layerName)
        }
    }

    private fun transformPoint(
        pt: Vec2,
        base: Vec2,
        insert: Vec2,
        sx: Double,
        sy: Double,
        rotRad: Double
    ): Vec2 {
        val dx = (pt.x - base.x) * sx
        val dy = (pt.y - base.y) * sy
        val c = cos(rotRad)
        val s = sin(rotRad)
        return Vec2(insert.x + dx * c - dy * s, insert.y + dx * s + dy * c)
    }

    private fun transformBlockChild(
        e: CadEntity,
        base: Vec2,
        ins: Vec2,
        sx: Double,
        sy: Double,
        rotRad: Double,
        fallbackLayer: String
    ): CadEntity {
        val avgScale = (abs(sx) + abs(sy)) * 0.5
        val rotDeg = Math.toDegrees(rotRad)
        val layer = if (e.layerName == "0") fallbackLayer else e.layerName
        return when (e) {
            is CadLine -> e.copy(
                id = CadEntity.newEntityId(),
                layerName = layer,
                start = transformPoint(e.start, base, ins, sx, sy, rotRad),
                end = transformPoint(e.end, base, ins, sx, sy, rotRad)
            )
            is CadPolyline -> e.copy(
                id = CadEntity.newEntityId(),
                layerName = layer,
                points = e.points.map { transformPoint(it, base, ins, sx, sy, rotRad) }
            )
            is CadCircle -> e.copy(
                id = CadEntity.newEntityId(),
                layerName = layer,
                center = transformPoint(e.center, base, ins, sx, sy, rotRad),
                radius = e.radius * avgScale
            )
            is CadArc -> e.copy(
                id = CadEntity.newEntityId(),
                layerName = layer,
                center = transformPoint(e.center, base, ins, sx, sy, rotRad),
                radius = e.radius * avgScale,
                startAngleDeg = e.startAngleDeg + rotDeg,
                endAngleDeg = e.endAngleDeg + rotDeg
            )
            is CadPoint -> e.copy(
                id = CadEntity.newEntityId(),
                layerName = layer,
                position = transformPoint(e.position, base, ins, sx, sy, rotRad)
            )
            is CadText -> e.copy(
                id = CadEntity.newEntityId(),
                layerName = layer,
                position = transformPoint(e.position, base, ins, sx, sy, rotRad),
                height = e.height * avgScale,
                rotationDeg = e.rotationDeg + rotDeg
            )
            else -> e.copyCommon(id = CadEntity.newEntityId(), layerName = layer)
        }
    }
}

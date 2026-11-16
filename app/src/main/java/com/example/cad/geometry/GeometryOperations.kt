package com.example.cad.geometry

import com.example.cad.model.Bounds2D
import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object GeometryOperations {

    fun translate(entity: CadEntity, delta: Vec2, newId: Boolean = false): CadEntity {
        val eid = if (newId) CadEntity.newEntityId() else entity.id
        return when (entity) {
            is CadLine -> entity.copy(id = eid, start = entity.start + delta, end = entity.end + delta)
            is CadPolyline -> entity.copy(id = eid, points = entity.points.map { it + delta })
            is CadCircle -> entity.copy(id = eid, center = entity.center + delta)
            is CadArc -> entity.copy(id = eid, center = entity.center + delta)
            is CadEllipse -> entity.copy(id = eid, center = entity.center + delta)
            is CadSpline -> entity.copy(id = eid, controlPoints = entity.controlPoints.map { it + delta })
            is CadPoint -> entity.copy(id = eid, position = entity.position + delta)
            is CadRay -> entity.copy(id = eid, origin = entity.origin + delta)
            is CadXLine -> entity.copy(id = eid, origin = entity.origin + delta)
            is CadText -> entity.copy(id = eid, position = entity.position + delta)
            is CadBlockReference -> entity.copy(id = eid, insertionPoint = entity.insertionPoint + delta)
            is CadDimension -> entity.copy(
                id = eid,
                p1 = entity.p1 + delta,
                p2 = entity.p2 + delta,
                p3 = entity.p3?.plus(delta),
                dimLinePoint = entity.dimLinePoint + delta
            )
        }
    }

    fun rotate(entity: CadEntity, pivot: Vec2, angleDeg: Double, newId: Boolean = false): CadEntity {
        val rad = Math.toRadians(angleDeg)
        val eid = if (newId) CadEntity.newEntityId() else entity.id
        return when (entity) {
            is CadLine -> entity.copy(
                id = eid,
                start = entity.start.rotateAround(pivot, rad),
                end = entity.end.rotateAround(pivot, rad)
            )
            is CadPolyline -> entity.copy(id = eid, points = entity.points.map { it.rotateAround(pivot, rad) })
            is CadCircle -> entity.copy(id = eid, center = entity.center.rotateAround(pivot, rad))
            is CadArc -> entity.copy(
                id = eid,
                center = entity.center.rotateAround(pivot, rad),
                startAngleDeg = (entity.startAngleDeg + angleDeg) % 360.0,
                endAngleDeg = (entity.endAngleDeg + angleDeg) % 360.0
            )
            is CadEllipse -> entity.copy(
                id = eid,
                center = entity.center.rotateAround(pivot, rad),
                majorAxisEndOffset = entity.majorAxisEndOffset.rotateAround(Vec2.ZERO, rad)
            )
            is CadSpline -> entity.copy(id = eid, controlPoints = entity.controlPoints.map { it.rotateAround(pivot, rad) })
            is CadPoint -> entity.copy(id = eid, position = entity.position.rotateAround(pivot, rad))
            is CadRay -> entity.copy(
                id = eid,
                origin = entity.origin.rotateAround(pivot, rad),
                direction = entity.direction.rotateAround(Vec2.ZERO, rad)
            )
            is CadXLine -> entity.copy(
                id = eid,
                origin = entity.origin.rotateAround(pivot, rad),
                direction = entity.direction.rotateAround(Vec2.ZERO, rad)
            )
            is CadText -> entity.copy(
                id = eid,
                position = entity.position.rotateAround(pivot, rad),
                rotationDeg = (entity.rotationDeg + angleDeg) % 360.0
            )
            is CadBlockReference -> entity.copy(
                id = eid,
                insertionPoint = entity.insertionPoint.rotateAround(pivot, rad),
                rotationDeg = (entity.rotationDeg + angleDeg) % 360.0
            )
            is CadDimension -> entity.copy(
                id = eid,
                p1 = entity.p1.rotateAround(pivot, rad),
                p2 = entity.p2.rotateAround(pivot, rad),
                p3 = entity.p3?.rotateAround(pivot, rad),
                dimLinePoint = entity.dimLinePoint.rotateAround(pivot, rad)
            )
        }
    }

    fun scale(entity: CadEntity, base: Vec2, factor: Double, newId: Boolean = false): CadEntity {
        val f = max(1e-4, factor)
        val eid = if (newId) CadEntity.newEntityId() else entity.id
        return when (entity) {
            is CadLine -> entity.copy(id = eid, start = entity.start.scaleAround(base, f), end = entity.end.scaleAround(base, f))
            is CadPolyline -> entity.copy(id = eid, points = entity.points.map { it.scaleAround(base, f) })
            is CadCircle -> entity.copy(id = eid, center = entity.center.scaleAround(base, f), radius = entity.radius * f)
            is CadArc -> entity.copy(id = eid, center = entity.center.scaleAround(base, f), radius = entity.radius * f)
            is CadEllipse -> entity.copy(
                id = eid,
                center = entity.center.scaleAround(base, f),
                majorAxisEndOffset = entity.majorAxisEndOffset * f
            )
            is CadSpline -> entity.copy(id = eid, controlPoints = entity.controlPoints.map { it.scaleAround(base, f) })
            is CadPoint -> entity.copy(id = eid, position = entity.position.scaleAround(base, f))
            is CadRay -> entity.copy(id = eid, origin = entity.origin.scaleAround(base, f))
            is CadXLine -> entity.copy(id = eid, origin = entity.origin.scaleAround(base, f))
            is CadText -> entity.copy(id = eid, position = entity.position.scaleAround(base, f), height = entity.height * f)
            is CadBlockReference -> entity.copy(
                id = eid,
                insertionPoint = entity.insertionPoint.scaleAround(base, f),
                scaleX = entity.scaleX * f,
                scaleY = entity.scaleY * f
            )
            is CadDimension -> entity.copy(
                id = eid,
                p1 = entity.p1.scaleAround(base, f),
                p2 = entity.p2.scaleAround(base, f),
                p3 = entity.p3?.scaleAround(base, f),
                dimLinePoint = entity.dimLinePoint.scaleAround(base, f)
            )
        }
    }

    fun mirror(entity: CadEntity, a: Vec2, b: Vec2, newId: Boolean = true): CadEntity {
        val eid = if (newId) CadEntity.newEntityId() else entity.id
        val lineAngleDeg = (b - a).angleDeg()
        return when (entity) {
            is CadLine -> entity.copy(id = eid, start = entity.start.mirrorAcross(a, b), end = entity.end.mirrorAcross(a, b))
            is CadPolyline -> entity.copy(id = eid, points = entity.points.map { it.mirrorAcross(a, b) })
            is CadCircle -> entity.copy(id = eid, center = entity.center.mirrorAcross(a, b))
            is CadArc -> {
                val newStart = 2.0 * lineAngleDeg - entity.endAngleDeg
                val newEnd = 2.0 * lineAngleDeg - entity.startAngleDeg
                entity.copy(
                    id = eid,
                    center = entity.center.mirrorAcross(a, b),
                    startAngleDeg = newStart,
                    endAngleDeg = newEnd
                )
            }
            is CadEllipse -> {
                val endPt = entity.center + entity.majorAxisEndOffset
                val newCenter = entity.center.mirrorAcross(a, b)
                val newEnd = endPt.mirrorAcross(a, b)
                entity.copy(id = eid, center = newCenter, majorAxisEndOffset = newEnd - newCenter)
            }
            is CadSpline -> entity.copy(id = eid, controlPoints = entity.controlPoints.map { it.mirrorAcross(a, b) })
            is CadPoint -> entity.copy(id = eid, position = entity.position.mirrorAcross(a, b))
            is CadRay -> {
                val o2 = (entity.origin + entity.direction).mirrorAcross(a, b)
                val o1 = entity.origin.mirrorAcross(a, b)
                entity.copy(id = eid, origin = o1, direction = (o2 - o1).normalized())
            }
            is CadXLine -> {
                val o2 = (entity.origin + entity.direction).mirrorAcross(a, b)
                val o1 = entity.origin.mirrorAcross(a, b)
                entity.copy(id = eid, origin = o1, direction = (o2 - o1).normalized())
            }
            is CadText -> entity.copy(id = eid, position = entity.position.mirrorAcross(a, b))
            is CadBlockReference -> entity.copy(id = eid, insertionPoint = entity.insertionPoint.mirrorAcross(a, b))
            is CadDimension -> entity.copy(
                id = eid,
                p1 = entity.p1.mirrorAcross(a, b),
                p2 = entity.p2.mirrorAcross(a, b),
                p3 = entity.p3?.mirrorAcross(a, b),
                dimLinePoint = entity.dimLinePoint.mirrorAcross(a, b)
            )
        }
    }

    fun offset(entity: CadEntity, distance: Double, sidePoint: Vec2): CadEntity? {
        if (distance <= 1e-6) return null
        return when (entity) {
            is CadLine -> {
                val dir = (entity.end - entity.start).normalized()
                val n = dir.perpendicular()
                val side = if ((sidePoint - entity.start).dot(n) >= 0.0) 1.0 else -1.0
                val shift = n * (distance * side)
                entity.copy(id = CadEntity.newEntityId(), start = entity.start + shift, end = entity.end + shift, isSelected = false)
            }
            is CadCircle -> {
                val d = sidePoint.distanceTo(entity.center)
                val newR = if (d >= entity.radius) entity.radius + distance else entity.radius - distance
                if (newR <= 1e-4) null
                else entity.copy(id = CadEntity.newEntityId(), radius = newR, isSelected = false)
            }
            is CadArc -> {
                val d = sidePoint.distanceTo(entity.center)
                val newR = if (d >= entity.radius) entity.radius + distance else entity.radius - distance
                if (newR <= 1e-4) null
                else entity.copy(id = CadEntity.newEntityId(), radius = newR, isSelected = false)
            }
            is CadPolyline -> {
                if (entity.points.size < 2) return null
                val pts = entity.points
                val nCount = pts.size
                // Determine overall offset sign from nearest segment
                var bestSeg = 0
                var bestDist = Double.POSITIVE_INFINITY
                val segCount = if (entity.isClosed) nCount else nCount - 1
                for (i in 0 until segCount) {
                    val (d, _) = GeometryEngine.pointToSegmentDistance(sidePoint, pts[i], pts[(i + 1) % nCount])
                    if (d < bestDist) {
                        bestDist = d
                        bestSeg = i
                    }
                }
                val segDir = (pts[(bestSeg + 1) % nCount] - pts[bestSeg]).normalized()
                val segNormal = segDir.perpendicular()
                val sign = if ((sidePoint - pts[bestSeg]).dot(segNormal) >= 0.0) 1.0 else -1.0

                val offsetLines = (0 until segCount).map { i ->
                    val a = pts[i]
                    val b = pts[(i + 1) % nCount]
                    val n = (b - a).normalized().perpendicular() * (distance * sign)
                    (a + n) to (b + n)
                }

                val newPts = mutableListOf<Vec2>()
                if (!entity.isClosed) {
                    newPts.add(offsetLines.first().first)
                    for (i in 0 until offsetLines.size - 1) {
                        val l1 = offsetLines[i]
                        val l2 = offsetLines[i + 1]
                        newPts.add(GeometryEngine.lineIntersection(l1.first, l1.second, l2.first, l2.second) ?: l1.second)
                    }
                    newPts.add(offsetLines.last().second)
                } else {
                    for (i in offsetLines.indices) {
                        val prev = offsetLines[(i - 1 + offsetLines.size) % offsetLines.size]
                        val curr = offsetLines[i]
                        newPts.add(GeometryEngine.lineIntersection(prev.first, prev.second, curr.first, curr.second) ?: curr.first)
                    }
                }
                entity.copy(id = CadEntity.newEntityId(), points = newPts, isSelected = false)
            }
            else -> null
        }
    }

    fun stretch(entity: CadEntity, window: Bounds2D, delta: Vec2): CadEntity {
        return when (entity) {
            is CadLine -> {
                val s = if (window.containsPoint(entity.start)) entity.start + delta else entity.start
                val e = if (window.containsPoint(entity.end)) entity.end + delta else entity.end
                entity.copy(start = s, end = e)
            }
            is CadPolyline -> entity.copy(
                points = entity.points.map { p -> if (window.containsPoint(p)) p + delta else p }
            )
            is CadSpline -> entity.copy(
                controlPoints = entity.controlPoints.map { p -> if (window.containsPoint(p)) p + delta else p }
            )
            else -> {
                val b = GeometryEngine.boundsOf(entity)
                if (window.contains(b)) translate(entity, delta) else entity
            }
        }
    }

    fun trimEntity(target: CadEntity, clickPoint: Vec2, others: List<CadEntity>): List<CadEntity>? {
        val cutters = others.filter { it.id != target.id }.flatMap { GeometryEngine.entityToSegments(it) }
        val circleCutters = others.filter { it.id != target.id }.mapNotNull { it as? CadCircle }
        return when (target) {
            is CadLine -> {
                val hits = mutableListOf<Vec2>()
                for ((a, b) in cutters) {
                    GeometryEngine.segmentIntersection(target.start, target.end, a, b)?.let { hits.add(it) }
                }
                for (c in circleCutters) {
                    hits.addAll(GeometryEngine.lineCircleIntersections(target.start, target.end, c.center, c.radius, segmentOnly = true))
                }
                if (hits.isEmpty()) return null
                val ab = target.end - target.start
                val len = ab.length()
                if (len < 1e-9) return null
                val u = ab / len
                val tClick = ((clickPoint - target.start).dot(u) / len).coerceIn(0.0, 1.0)
                val ts = (listOf(0.0, 1.0) + hits.map { ((it - target.start).dot(u) / len).coerceIn(0.0, 1.0) })
                    .sorted()
                    .fold(mutableListOf<Double>()) { acc, t ->
                        if (acc.isEmpty() || abs(acc.last() - t) > 1e-5) acc.add(t)
                        acc
                    }
                if (ts.size <= 2) return null
                var clickedIdx = 0
                for (i in 0 until ts.size - 1) {
                    if (tClick >= ts[i] - 1e-5 && tClick <= ts[i + 1] + 1e-5) {
                        clickedIdx = i
                        break
                    }
                }
                val result = mutableListOf<CadEntity>()
                if (clickedIdx > 0) {
                    result.add(target.copy(id = CadEntity.newEntityId(), start = target.start, end = target.start + ab * ts[clickedIdx]))
                }
                if (clickedIdx < ts.size - 2) {
                    result.add(target.copy(id = CadEntity.newEntityId(), start = target.start + ab * ts[clickedIdx + 1], end = target.end))
                }
                result
            }
            else -> null
        }
    }

    fun extendEntity(target: CadLine, clickPoint: Vec2, others: List<CadEntity>): CadLine? {
        val extendStart = clickPoint.distanceTo(target.start) < clickPoint.distanceTo(target.end)
        val anchor = if (extendStart) target.end else target.start
        val moving = if (extendStart) target.start else target.end
        val dir = (moving - anchor).normalized()
        val farEnd = moving + dir * 100000.0

        val candidates = mutableListOf<Vec2>()
        for (o in others) {
            if (o.id == target.id) continue
            for ((a, b) in GeometryEngine.entityToSegments(o)) {
                GeometryEngine.segmentIntersection(moving, farEnd, a, b)?.let {
                    if (it.distanceTo(moving) > 1e-4) candidates.add(it)
                }
            }
            if (o is CadCircle) {
                GeometryEngine.lineCircleIntersections(moving, farEnd, o.center, o.radius, segmentOnly = true).forEach {
                    if (it.distanceTo(moving) > 1e-4) candidates.add(it)
                }
            }
        }
        val nearest = candidates.minByOrNull { it.distanceTo(moving) } ?: return null
        return if (extendStart) target.copy(start = nearest) else target.copy(end = nearest)
    }

    fun filletLines(l1: CadLine, l2: CadLine, radius: Double): Triple<CadLine, CadLine, CadArc?>? {
        val inter = GeometryEngine.lineIntersection(l1.start, l1.end, l2.start, l2.end) ?: return null
        val d1 = if (inter.distanceTo(l1.start) >= inter.distanceTo(l1.end)) l1.start else l1.end
        val d2 = if (inter.distanceTo(l2.start) >= inter.distanceTo(l2.end)) l2.start else l2.end
        val u1 = (d1 - inter).normalized()
        val u2 = (d2 - inter).normalized()
        val dot = u1.dot(u2).coerceIn(-0.9999, 0.9999)
        val angle = kotlin.math.acos(dot)
        if (angle < 1e-3 || angle > PI - 1e-3) return null

        if (radius <= 1e-5) {
            // Zero-radius corner fillet
            val nl1 = if (inter.distanceTo(l1.start) >= inter.distanceTo(l1.end)) l1.copy(end = inter) else l1.copy(start = inter)
            val nl2 = if (inter.distanceTo(l2.start) >= inter.distanceTo(l2.end)) l2.copy(end = inter) else l2.copy(start = inter)
            return Triple(nl1, nl2, null)
        }

        val tangentDist = radius / kotlin.math.tan(angle * 0.5)
        val t1 = inter + u1 * tangentDist
        val t2 = inter + u2 * tangentDist
        val bisector = (u1 + u2).normalized()
        val centerDist = radius / kotlin.math.sin(angle * 0.5)
        val center = inter + bisector * centerDist

        val a1 = (t1 - center).angleDeg()
        val a2 = (t2 - center).angleDeg()
        val sweep12 = GeometryEngine.normalizeSweepDeg(a1, a2)
        val (arcStart, arcEnd) = if (sweep12 <= 180.0) a1 to a2 else a2 to a1

        val nl1 = if (inter.distanceTo(l1.start) >= inter.distanceTo(l1.end)) l1.copy(end = t1) else l1.copy(start = t1)
        val nl2 = if (inter.distanceTo(l2.start) >= inter.distanceTo(l2.end)) l2.copy(end = t2) else l2.copy(start = t2)
        val arc = CadArc(
            layerName = l1.layerName,
            colorArgb = l1.colorArgb,
            center = center,
            radius = radius,
            startAngleDeg = arcStart,
            endAngleDeg = arcEnd
        )
        return Triple(nl1, nl2, arc)
    }

    fun chamferLines(l1: CadLine, l2: CadLine, dist1: Double, dist2: Double): Triple<CadLine, CadLine, CadLine>? {
        val inter = GeometryEngine.lineIntersection(l1.start, l1.end, l2.start, l2.end) ?: return null
        val d1 = if (inter.distanceTo(l1.start) >= inter.distanceTo(l1.end)) l1.start else l1.end
        val d2 = if (inter.distanceTo(l2.start) >= inter.distanceTo(l2.end)) l2.start else l2.end
        val u1 = (d1 - inter).normalized()
        val u2 = (d2 - inter).normalized()
        val p1 = inter + u1 * dist1
        val p2 = inter + u2 * dist2
        val nl1 = if (inter.distanceTo(l1.start) >= inter.distanceTo(l1.end)) l1.copy(end = p1) else l1.copy(start = p1)
        val nl2 = if (inter.distanceTo(l2.start) >= inter.distanceTo(l2.end)) l2.copy(end = p2) else l2.copy(start = p2)
        val bevel = CadLine(layerName = l1.layerName, colorArgb = l1.colorArgb, start = p1, end = p2)
        return Triple(nl1, nl2, bevel)
    }

    fun arrayRectangular(
        entities: List<CadEntity>,
        rows: Int,
        cols: Int,
        rowSpacing: Double,
        colSpacing: Double
    ): List<CadEntity> {
        val out = mutableListOf<CadEntity>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (r == 0 && c == 0) continue
                val delta = Vec2(c * colSpacing, r * rowSpacing)
                entities.forEach { out.add(translate(it, delta, newId = true).copyCommon(isSelected = false)) }
            }
        }
        return out
    }

    fun arrayPolar(
        entities: List<CadEntity>,
        center: Vec2,
        count: Int,
        totalAngleDeg: Double
    ): List<CadEntity> {
        if (count <= 1) return emptyList()
        val step = if (abs(totalAngleDeg - 360.0) < 1e-3) totalAngleDeg / count else totalAngleDeg / (count - 1)
        val out = mutableListOf<CadEntity>()
        for (i in 1 until count) {
            val angle = step * i
            entities.forEach { out.add(rotate(it, center, angle, newId = true).copyCommon(isSelected = false)) }
        }
        return out
    }

    fun explode(entity: CadEntity, blocks: Map<String, CadBlockDefinition>): List<CadEntity> {
        return when (entity) {
            is CadPolyline -> {
                if (entity.points.size < 2) return listOf(entity)
                val count = if (entity.isClosed) entity.points.size else entity.points.size - 1
                (0 until count).map { i ->
                    CadLine(
                        layerName = entity.layerName,
                        colorArgb = entity.colorArgb,
                        lineType = entity.lineType,
                        lineWeightMm = entity.lineWeightMm,
                        start = entity.points[i],
                        end = entity.points[(i + 1) % entity.points.size]
                    )
                }
            }
            is CadBlockReference -> {
                val def = blocks[entity.blockName] ?: return listOf(entity)
                GeometryEngine.explodeBlockReference(entity, def)
            }
            is CadDimension -> {
                listOf(
                    CadLine(layerName = entity.layerName, colorArgb = entity.colorArgb, start = entity.p1, end = entity.dimLinePoint),
                    CadLine(layerName = entity.layerName, colorArgb = entity.colorArgb, start = entity.p2, end = entity.dimLinePoint)
                )
            }
            else -> listOf(entity)
        }
    }

    fun joinEntities(entities: List<CadEntity>): CadPolyline? {
        val segments = mutableListOf<Pair<Vec2, Vec2>>()
        for (e in entities) {
            when (e) {
                is CadLine -> segments.add(e.start to e.end)
                is CadPolyline -> {
                    val count = if (e.isClosed) e.points.size else e.points.size - 1
                    for (i in 0 until count) {
                        segments.add(e.points[i] to e.points[(i + 1) % e.points.size])
                    }
                }
                else -> Unit
            }
        }
        if (segments.isEmpty()) return null
        val chain = mutableListOf(segments[0].first, segments[0].second)
        val remaining = segments.drop(1).toMutableList()
        val tol = 1e-2
        var progress = true
        while (remaining.isNotEmpty() && progress) {
            progress = false
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val seg = iter.next()
                when {
                    seg.first.distanceTo(chain.last()) <= tol -> {
                        chain.add(seg.second); iter.remove(); progress = true
                    }
                    seg.second.distanceTo(chain.last()) <= tol -> {
                        chain.add(seg.first); iter.remove(); progress = true
                    }
                    seg.second.distanceTo(chain.first()) <= tol -> {
                        chain.add(0, seg.first); iter.remove(); progress = true
                    }
                    seg.first.distanceTo(chain.first()) <= tol -> {
                        chain.add(0, seg.second); iter.remove(); progress = true
                    }
                }
            }
        }
        val isClosed = chain.size > 2 && chain.first().distanceTo(chain.last()) <= tol
        val finalPts = if (isClosed) chain.dropLast(1) else chain
        val first = entities.first()
        return CadPolyline(
            layerName = first.layerName,
            colorArgb = first.colorArgb,
            lineType = first.lineType,
            lineWeightMm = first.lineWeightMm,
            points = finalPts,
            isClosed = isClosed
        )
    }

    fun breakEntity(entity: CadEntity, breakPoint: Vec2): List<CadEntity>? {
        return when (entity) {
            is CadLine -> {
                if (breakPoint.distanceTo(entity.start) < 1e-3 || breakPoint.distanceTo(entity.end) < 1e-3) return null
                val (_, proj) = GeometryEngine.pointToSegmentDistance(breakPoint, entity.start, entity.end)
                listOf(
                    entity.copy(id = CadEntity.newEntityId(), end = proj, isSelected = false),
                    entity.copy(id = CadEntity.newEntityId(), start = proj, isSelected = false)
                )
            }
            is CadArc -> {
                val angle = (breakPoint - entity.center).angleDeg()
                listOf(
                    entity.copy(id = CadEntity.newEntityId(), endAngleDeg = angle, isSelected = false),
                    entity.copy(id = CadEntity.newEntityId(), startAngleDeg = angle, isSelected = false)
                )
            }
            is CadCircle -> {
                val angle = (breakPoint - entity.center).angleDeg()
                listOf(
                    CadArc(
                        layerName = entity.layerName,
                        colorArgb = entity.colorArgb,
                        lineType = entity.lineType,
                        lineWeightMm = entity.lineWeightMm,
                        center = entity.center,
                        radius = entity.radius,
                        startAngleDeg = angle + 5.0,
                        endAngleDeg = angle + 355.0
                    )
                )
            }
            is CadPolyline -> {
                if (entity.points.size < 2) return null
                var bestSeg = 0
                var bestDist = Double.POSITIVE_INFINITY
                var bestPt = breakPoint
                for (i in 0 until entity.points.size - 1) {
                    val (d, pt) = GeometryEngine.pointToSegmentDistance(breakPoint, entity.points[i], entity.points[i + 1])
                    if (d < bestDist) {
                        bestDist = d; bestSeg = i; bestPt = pt
                    }
                }
                val part1 = entity.points.subList(0, bestSeg + 1) + bestPt
                val part2 = listOf(bestPt) + entity.points.subList(bestSeg + 1, entity.points.size)
                listOf(
                    entity.copy(id = CadEntity.newEntityId(), points = part1, isClosed = false, isSelected = false),
                    entity.copy(id = CadEntity.newEntityId(), points = part2, isClosed = false, isSelected = false)
                )
            }
            else -> null
        }
    }

    fun gripsOf(entity: CadEntity): List<Vec2> {
        return when (entity) {
            is CadLine -> listOf(entity.start, (entity.start + entity.end) * 0.5, entity.end)
            is CadPolyline -> entity.points
            is CadCircle -> listOf(
                entity.center,
                Vec2(entity.center.x + entity.radius, entity.center.y),
                Vec2(entity.center.x, entity.center.y + entity.radius),
                Vec2(entity.center.x - entity.radius, entity.center.y),
                Vec2(entity.center.x, entity.center.y - entity.radius)
            )
            is CadArc -> {
                val s = Vec2.fromPolar(entity.center, entity.radius, entity.startAngleDeg)
                val e = Vec2.fromPolar(entity.center, entity.radius, entity.endAngleDeg)
                val m = Vec2.fromPolar(entity.center, entity.radius, entity.startAngleDeg + GeometryEngine.normalizeSweepDeg(entity.startAngleDeg, entity.endAngleDeg) * 0.5)
                listOf(entity.center, s, m, e)
            }
            is CadEllipse -> listOf(entity.center, entity.center + entity.majorAxisEndOffset)
            is CadSpline -> entity.controlPoints
            is CadPoint -> listOf(entity.position)
            is CadRay -> listOf(entity.origin, entity.origin + entity.direction.normalized() * 20.0)
            is CadXLine -> listOf(entity.origin, entity.origin + entity.direction.normalized() * 20.0)
            is CadText -> listOf(entity.position)
            is CadBlockReference -> listOf(entity.insertionPoint)
            is CadDimension -> listOfNotNull(entity.p1, entity.p2, entity.dimLinePoint, entity.p3)
        }
    }

    fun moveGrip(entity: CadEntity, gripIndex: Int, newPos: Vec2): CadEntity {
        return when (entity) {
            is CadLine -> when (gripIndex) {
                0 -> entity.copy(start = newPos)
                1 -> translate(entity, newPos - (entity.start + entity.end) * 0.5)
                2 -> entity.copy(end = newPos)
                else -> entity
            }
            is CadPolyline -> {
                if (gripIndex !in entity.points.indices) return entity
                val pts = entity.points.toMutableList()
                pts[gripIndex] = newPos
                entity.copy(points = pts)
            }
            is CadCircle -> when (gripIndex) {
                0 -> entity.copy(center = newPos)
                in 1..4 -> entity.copy(radius = max(0.5, entity.center.distanceTo(newPos)))
                else -> entity
            }
            is CadArc -> when (gripIndex) {
                0 -> entity.copy(center = newPos)
                1 -> entity.copy(startAngleDeg = (newPos - entity.center).angleDeg())
                2 -> entity.copy(radius = max(0.5, entity.center.distanceTo(newPos)))
                3 -> entity.copy(endAngleDeg = (newPos - entity.center).angleDeg())
                else -> entity
            }
            is CadEllipse -> when (gripIndex) {
                0 -> entity.copy(center = newPos)
                1 -> entity.copy(majorAxisEndOffset = newPos - entity.center)
                else -> entity
            }
            is CadSpline -> {
                if (gripIndex !in entity.controlPoints.indices) return entity
                val pts = entity.controlPoints.toMutableList()
                pts[gripIndex] = newPos
                entity.copy(controlPoints = pts)
            }
            is CadPoint -> entity.copy(position = newPos)
            is CadRay -> if (gripIndex == 0) entity.copy(origin = newPos) else entity.copy(direction = (newPos - entity.origin).normalized())
            is CadXLine -> if (gripIndex == 0) entity.copy(origin = newPos) else entity.copy(direction = (newPos - entity.origin).normalized())
            is CadText -> entity.copy(position = newPos)
            is CadBlockReference -> entity.copy(insertionPoint = newPos)
            is CadDimension -> when (gripIndex) {
                0 -> entity.copy(p1 = newPos)
                1 -> entity.copy(p2 = newPos)
                2 -> entity.copy(dimLinePoint = newPos)
                3 -> entity.copy(p3 = newPos)
                else -> entity
            }
        }
    }

    fun updateAssociativeDimensions(entities: List<CadEntity>): List<CadEntity> {
        val byId = entities.associateBy { it.id }
        return entities.map { entity ->
            if (entity is CadDimension && entity.associatedEntityIds.isNotEmpty()) {
                val target = byId[entity.associatedEntityIds.first()] ?: return@map entity
                when {
                    target is CadLine && (entity.dimType == DimensionType.LINEAR || entity.dimType == DimensionType.ALIGNED) ->
                        entity.copy(p1 = target.start, p2 = target.end)
                    target is CadCircle && (entity.dimType == DimensionType.RADIUS || entity.dimType == DimensionType.DIAMETER) -> {
                        val dir = (entity.p2 - entity.p1).normalized()
                        entity.copy(p1 = target.center, p2 = target.center + dir * target.radius)
                    }
                    target is CadArc && (entity.dimType == DimensionType.RADIUS || entity.dimType == DimensionType.DIAMETER || entity.dimType == DimensionType.ARC_LENGTH) -> {
                        val pStart = Vec2.fromPolar(target.center, target.radius, target.startAngleDeg)
                        val pEnd = Vec2.fromPolar(target.center, target.radius, target.endAngleDeg)
                        if (entity.dimType == DimensionType.ARC_LENGTH) {
                            entity.copy(p1 = target.center, p2 = pStart, p3 = pEnd)
                        } else {
                            val dir = (entity.p2 - entity.p1).normalized()
                            entity.copy(p1 = target.center, p2 = target.center + dir * target.radius)
                        }
                    }
                    else -> entity
                }
            } else entity
        }
    }
}

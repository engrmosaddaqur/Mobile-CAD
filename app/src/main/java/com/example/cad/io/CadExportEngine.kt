package com.example.cad.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.cad.geometry.GeometryEngine
import com.example.cad.model.Bounds2D
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object CadExportEngine {

    fun exportToPdf(doc: CadDocument, outputStream: OutputStream, whitePaper: Boolean = true) {
        // A4 Landscape points (72 dpi): 842 x 595
        val pageW = 842.0
        val pageH = 595.0
        val bounds = GeometryEngine.boundsOfAll(doc.entities, doc.blocks)
        val pad = 44.0
        val availW = pageW - pad * 2.0
        val availH = pageH - pad * 2.0 - 40.0
        val bw = max(1e-3, bounds.width)
        val bh = max(1e-3, bounds.height)
        val scale = min(availW / bw, availH / bh)
        val offX = pad + (availW - bw * scale) * 0.5 - bounds.minX * scale
        val offY = pad + 32.0 + (availH - bh * scale) * 0.5 - bounds.minY * scale

        fun px(wx: Double): Double = wx * scale + offX
        fun py(wy: Double): Double = wy * scale + offY // PDF coordinate system is Y-up just like CAD!
        fun f(d: Double): String = String.format(Locale.US, "%.3f", d)

        val sb = StringBuilder()
        if (!whitePaper) {
            sb.append("0.043 0.063 0.090 rg 0 0 ${f(pageW)} ${f(pageH)} re f\n")
        }

        val layerMap = doc.layers.associateBy { it.name.uppercase() }
        for (entity in doc.entities) {
            val layer = layerMap[entity.layerName.uppercase()]
            if (layer != null && (!layer.isOn || layer.isFrozen)) continue

            var argb = entity.colorArgb ?: layer?.colorArgb ?: Color.WHITE
            if (whitePaper && (argb and 0xFFFFFF) == 0xFFFFFF) {
                argb = Color.rgb(16, 25, 35)
            }
            val r = ((argb ushr 16) and 0xFF) / 255.0
            val g = ((argb ushr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            val lw = max(0.45, (entity.lineWeightMm ?: layer?.lineWeightMm ?: 0.25f) * 2.2)

            sb.append("${f(r)} ${f(g)} ${f(b)} RG ${f(r)} ${f(g)} ${f(b)} rg ${f(lw)} w 1 J 1 j\n")

            val lt = entity.lineType ?: layer?.lineType ?: CadLineType.CONTINUOUS
            when (lt) {
                CadLineType.DASHED -> sb.append("[6 3] 0 d\n")
                CadLineType.DOTTED -> sb.append("[1.5 3] 0 d\n")
                CadLineType.DASHDOT -> sb.append("[7 2.5 1.5 2.5] 0 d\n")
                CadLineType.CENTER -> sb.append("[10 3 2.5 3] 0 d\n")
                CadLineType.HIDDEN -> sb.append("[4 3] 0 d\n")
                CadLineType.CONTINUOUS -> sb.append("[] 0 d\n")
            }

            when (entity) {
                is CadLine -> sb.append("${f(px(entity.start.x))} ${f(py(entity.start.y))} m ${f(px(entity.end.x))} ${f(py(entity.end.y))} l S\n")
                is CadPolyline -> if (entity.points.size >= 2) {
                    sb.append("${f(px(entity.points[0].x))} ${f(py(entity.points[0].y))} m ")
                    for (i in 1 until entity.points.size) {
                        sb.append("${f(px(entity.points[i].x))} ${f(py(entity.points[i].y))} l ")
                    }
                    sb.append(if (entity.isClosed) "s\n" else "S\n")
                }
                is CadCircle -> {
                    val pts = (0..48).map { i ->
                        val a = 2.0 * Math.PI * i / 48
                        Vec2(entity.center.x + entity.radius * kotlin.math.cos(a), entity.center.y + entity.radius * kotlin.math.sin(a))
                    }
                    sb.append("${f(px(pts[0].x))} ${f(py(pts[0].y))} m ")
                    for (i in 1 until pts.size) sb.append("${f(px(pts[i].x))} ${f(py(pts[i].y))} l ")
                    sb.append("s\n")
                }
                is CadArc -> {
                    val pts = GeometryEngine.sampleArcPoints(entity, 36)
                    if (pts.size >= 2) {
                        sb.append("${f(px(pts[0].x))} ${f(py(pts[0].y))} m ")
                        for (i in 1 until pts.size) sb.append("${f(px(pts[i].x))} ${f(py(pts[i].y))} l ")
                        sb.append("S\n")
                    }
                }
                is CadEllipse -> {
                    val pts = GeometryEngine.sampleEllipsePoints(entity, 48)
                    if (pts.size >= 2) {
                        sb.append("${f(px(pts[0].x))} ${f(py(pts[0].y))} m ")
                        for (i in 1 until pts.size) sb.append("${f(px(pts[i].x))} ${f(py(pts[i].y))} l ")
                        sb.append("S\n")
                    }
                }
                is CadSpline -> {
                    val pts = GeometryEngine.sampleSplinePoints(entity, 10)
                    if (pts.size >= 2) {
                        sb.append("${f(px(pts[0].x))} ${f(py(pts[0].y))} m ")
                        for (i in 1 until pts.size) sb.append("${f(px(pts[i].x))} ${f(py(pts[i].y))} l ")
                        sb.append("S\n")
                    }
                }
                is CadText -> {
                    val safe = entity.text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                    val sz = max(7.0, entity.height * scale)
                    sb.append("BT /F1 ${f(sz)} Tf ${f(px(entity.position.x))} ${f(py(entity.position.y))} Td ($safe) Tj ET\n")
                }
                is CadDimension -> {
                    val valNum = GeometryEngine.computeDimensionValue(entity, doc.dimensionStyle.scaleFactor)
                    val label = (entity.textOverride ?: formatDimText(entity.dimType, valNum, doc.dimensionStyle.decimalPrecision, doc.units.shortLabel))
                        .replace("°", " deg").replace("Ø", "DIA ").replace("⌒", "ARC ")
                    sb.append("${f(px(entity.p1.x))} ${f(py(entity.p1.y))} m ${f(px(entity.dimLinePoint.x))} ${f(py(entity.dimLinePoint.y))} l S\n")
                    sb.append("${f(px(entity.p2.x))} ${f(py(entity.p2.y))} m ${f(px(entity.dimLinePoint.x))} ${f(py(entity.dimLinePoint.y))} l S\n")
                    sb.append("BT /F1 9.0 Tf ${f(px(entity.dimLinePoint.x))} ${f(py(entity.dimLinePoint.y) + 4.0)} Td ($label) Tj ET\n")
                }
                is CadBlockReference -> {
                    val def = doc.blocks[entity.blockName]
                    if (def != null) {
                        for (sub in GeometryEngine.explodeBlockReference(entity, def)) {
                            for ((a, b) in GeometryEngine.entityToSegments(sub, doc.blocks)) {
                                sb.append("${f(px(a.x))} ${f(py(a.y))} m ${f(px(b.x))} ${f(py(b.y))} l S\n")
                            }
                        }
                    }
                }
                else -> Unit
            }
        }

        // Engineering Title Block border & footer
        sb.append("0.2 0.35 0.5 RG 0.8 w [] 0 d 20 20 ${f(pageW - 40)} ${f(pageH - 40)} re S\n")
        val safeTitle = "${doc.name} | ${doc.activeLayout.name} | Units: ${doc.units.shortLabel} | Entities: ${doc.entities.size}"
        sb.append("BT /F1 9.0 Tf 28 28 Td ($safeTitle) Tj ET\n")

        val streamBytes = sb.toString().toByteArray(Charsets.US_ASCII)
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
            "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
            "5 0 obj\n<< /Length ${streamBytes.size} >>\nstream\n${sb}endstream\nendobj\n"
        )

        val pdfHeader = "%PDF-1.4\n"
        val body = StringBuilder(pdfHeader)
        val offsets = mutableListOf<Int>()
        for (obj in objects) {
            offsets.add(body.length)
            body.append(obj)
        }
        val xrefPos = body.length
        body.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) {
            body.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        body.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        outputStream.write(body.toString().toByteArray(Charsets.US_ASCII))
        outputStream.flush()
    }

    fun exportToRaster(
        doc: CadDocument,
        outputStream: OutputStream,
        format: Bitmap.CompressFormat,
        widthPx: Int = 2048,
        heightPx: Int = 1440,
        backgroundColor: Int = Color.rgb(11, 16, 23),
        invertWhiteLines: Boolean = false
    ) {
        val bmp = renderThumbnailBitmap(
            doc = doc,
            widthPx = widthPx,
            heightPx = heightPx,
            backgroundColor = backgroundColor,
            invertWhiteLines = invertWhiteLines
        )
        bmp.compress(format, 95, outputStream)
        bmp.recycle()
    }

    fun renderThumbnailBitmap(
        doc: CadDocument,
        widthPx: Int = 480,
        heightPx: Int = 320,
        backgroundColor: Int = Color.rgb(11, 16, 23),
        invertWhiteLines: Boolean = false,
        precomputedBounds: Bounds2D? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        renderToAndroidCanvas(
            doc = doc,
            canvas = canvas,
            widthPx = widthPx,
            heightPx = heightPx,
            backgroundColor = backgroundColor,
            invertWhiteLines = invertWhiteLines,
            drawTitleBlock = false,
            precomputedBounds = precomputedBounds,
            isThumbnail = true
        )
        return bmp
    }

    fun exportToSvg(doc: CadDocument, outputStream: OutputStream, whiteBackground: Boolean = false) {
        val w = OutputStreamWriter(outputStream)
        val bounds = GeometryEngine.boundsOfAll(doc.entities, doc.blocks).expand(10.0)
        val width = max(10.0, bounds.width)
        val height = max(10.0, bounds.height)
        val bgHex = if (whiteBackground) "#FFFFFF" else "#080C12"

        fun fmt(d: Double): String = String.format(Locale.US, "%.3f", d)
        fun sy(y: Double): Double = bounds.maxY - y // SVG Y-down from CAD Y-up
        fun sx(x: Double): Double = x - bounds.minX

        fun colorHex(argb: Int): String {
            var c = argb and 0xFFFFFF
            if (whiteBackground && c == 0xFFFFFF) c = 0x111827
            return String.format(Locale.US, "#%06X", c)
        }

        w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        w.write("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${fmt(width)} ${fmt(height)}" width="${fmt(width)}" height="${fmt(height)}">""" + "\n")
        w.write("""  <rect width="100%" height="100%" fill="$bgHex" />""" + "\n")

        val visibleLayers = doc.layers.filter { it.isOn && !it.isFrozen }.associateBy { it.name }
        for ((layerName, layer) in visibleLayers) {
            val layerEntities = doc.entities.filter { it.layerName.equals(layerName, ignoreCase = true) }
            if (layerEntities.isEmpty()) continue
            w.write("""  <g id="layer_${layerName.replace("\"", "_")}">""" + "\n")
            for (entity in layerEntities) {
                val resolvedColor = colorHex(entity.colorArgb ?: layer.colorArgb)
                val resolvedLw = max(0.35, (entity.lineWeightMm ?: layer.lineWeightMm).toDouble() * 1.6)
                val resolvedLt = entity.lineType ?: layer.lineType
                val dashAttr = when (resolvedLt) {
                    CadLineType.DASHED -> """ stroke-dasharray="4,2""""
                    CadLineType.DOTTED -> """ stroke-dasharray="1,2""""
                    CadLineType.DASHDOT -> """ stroke-dasharray="5,2,1,2""""
                    CadLineType.CENTER -> """ stroke-dasharray="8,2,2,2""""
                    CadLineType.HIDDEN -> """ stroke-dasharray="3,2""""
                    CadLineType.CONTINUOUS -> ""
                }
                val opacityAttr = if (entity.transparency > 0f) """ stroke-opacity="${fmt(1.0 - entity.transparency)}"""" else ""

                when (entity) {
                    is CadLine -> w.write("""    <line x1="${fmt(sx(entity.start.x))}" y1="${fmt(sy(entity.start.y))}" x2="${fmt(sx(entity.end.x))}" y2="${fmt(sy(entity.end.y))}" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}"$dashAttr$opacityAttr />""" + "\n")
                    is CadPolyline -> {
                        val ptsStr = entity.points.joinToString(" ") { "${fmt(sx(it.x))},${fmt(sy(it.y))}" }
                        val tag = if (entity.isClosed) "polygon" else "polyline"
                        w.write("""    <$tag points="$ptsStr" fill="none" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}"$dashAttr$opacityAttr />""" + "\n")
                    }
                    is CadCircle -> w.write("""    <circle cx="${fmt(sx(entity.center.x))}" cy="${fmt(sy(entity.center.y))}" r="${fmt(entity.radius)}" fill="none" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}"$dashAttr$opacityAttr />""" + "\n")
                    is CadArc -> {
                        val pts = GeometryEngine.sampleArcPoints(entity, 36)
                        val ptsStr = pts.joinToString(" ") { "${fmt(sx(it.x))},${fmt(sy(it.y))}" }
                        w.write("""    <polyline points="$ptsStr" fill="none" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}"$dashAttr$opacityAttr />""" + "\n")
                    }
                    is CadEllipse -> {
                        val pts = GeometryEngine.sampleEllipsePoints(entity, 48)
                        val ptsStr = pts.joinToString(" ") { "${fmt(sx(it.x))},${fmt(sy(it.y))}" }
                        w.write("""    <polyline points="$ptsStr" fill="none" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}"$dashAttr$opacityAttr />""" + "\n")
                    }
                    is CadSpline -> {
                        val pts = GeometryEngine.sampleSplinePoints(entity, 10)
                        val ptsStr = pts.joinToString(" ") { "${fmt(sx(it.x))},${fmt(sy(it.y))}" }
                        w.write("""    <polyline points="$ptsStr" fill="none" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}"$dashAttr$opacityAttr />""" + "\n")
                    }
                    is CadPoint -> w.write("""    <circle cx="${fmt(sx(entity.position.x))}" cy="${fmt(sy(entity.position.y))}" r="${fmt(resolvedLw * 1.5)}" fill="$resolvedColor" />""" + "\n")
                    is CadText -> {
                        val esc = entity.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        w.write("""    <text x="${fmt(sx(entity.position.x))}" y="${fmt(sy(entity.position.y))}" fill="$resolvedColor" font-family="monospace" font-size="${fmt(entity.height)}">$esc</text>""" + "\n")
                    }
                    is CadDimension -> {
                        val valNum = GeometryEngine.computeDimensionValue(entity, doc.dimensionStyle.scaleFactor)
                        val label = entity.textOverride ?: formatDimText(entity.dimType, valNum, doc.dimensionStyle.decimalPrecision, doc.units.shortLabel)
                        w.write("""    <line x1="${fmt(sx(entity.p1.x))}" y1="${fmt(sy(entity.p1.y))}" x2="${fmt(sx(entity.dimLinePoint.x))}" y2="${fmt(sy(entity.dimLinePoint.y))}" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw * 0.8)}" />""" + "\n")
                        w.write("""    <line x1="${fmt(sx(entity.p2.x))}" y1="${fmt(sy(entity.p2.y))}" x2="${fmt(sx(entity.dimLinePoint.x))}" y2="${fmt(sy(entity.dimLinePoint.y))}" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw * 0.8)}" />""" + "\n")
                        w.write("""    <text x="${fmt(sx(entity.dimLinePoint.x))}" y="${fmt(sy(entity.dimLinePoint.y))}" fill="$resolvedColor" font-family="monospace" font-size="${fmt(doc.dimensionStyle.textHeight)}">$label</text>""" + "\n")
                    }
                    is CadBlockReference -> {
                        val def = doc.blocks[entity.blockName]
                        if (def != null) {
                            for (sub in GeometryEngine.explodeBlockReference(entity, def)) {
                                for ((a, b) in GeometryEngine.entityToSegments(sub, doc.blocks)) {
                                    w.write("""    <line x1="${fmt(sx(a.x))}" y1="${fmt(sy(a.y))}" x2="${fmt(sx(b.x))}" y2="${fmt(sy(b.y))}" stroke="$resolvedColor" stroke-width="${fmt(resolvedLw)}" />""" + "\n")
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            w.write("  </g>\n")
        }
        w.write("</svg>\n")
        w.flush()
    }

    private fun renderToAndroidCanvas(
        doc: CadDocument,
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        backgroundColor: Int,
        invertWhiteLines: Boolean,
        drawTitleBlock: Boolean,
        precomputedBounds: Bounds2D? = null,
        isThumbnail: Boolean = false
    ) {
        canvas.drawColor(backgroundColor)
        val bounds = precomputedBounds ?: GeometryEngine.boundsOfAll(doc.entities, doc.blocks)
        val pad = if (drawTitleBlock) 48f else max(16f, min(widthPx, heightPx) * 0.08f)
        val availW = max(10f, widthPx - pad * 2f)
        val availH = max(10f, heightPx - pad * 2f - (if (drawTitleBlock) 54f else 0f))
        val bw = max(1e-3, bounds.width)
        val bh = max(1e-3, bounds.height)
        val scale = min(availW / bw, availH / bh)
        val offsetX = pad + (availW - bw * scale) * 0.5 - bounds.minX * scale
        val offsetY = pad + (availH - bh * scale) * 0.5 + bounds.maxY * scale

        fun toX(wx: Double): Float = (wx * scale + offsetX).toFloat()
        fun toY(wy: Double): Float = (offsetY - wy * scale).toFloat()

        val layerMap = doc.layers.associateBy { it.name.uppercase() }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            isSubpixelText = true
        }

        val step = if (isThumbnail && doc.entities.size > 3500) {
            max(1, doc.entities.size / 3500)
        } else 1

        var idx = 0
        while (idx < doc.entities.size) {
            val entity = doc.entities[idx]
            idx += step
            val layer: CadLayer? = layerMap[entity.layerName.uppercase()]
            if (layer != null && (!layer.isOn || layer.isFrozen)) continue

            var argb = entity.colorArgb ?: layer?.colorArgb ?: Color.WHITE
            if (invertWhiteLines && (argb and 0xFFFFFF) == 0xFFFFFF) {
                argb = Color.rgb(16, 25, 35)
            }
            val alpha = ((1f - entity.transparency.coerceIn(0f, 0.9f)) * 255).toInt()
            paint.color = (argb and 0x00FFFFFF) or (alpha shl 24)
            val lw = entity.lineWeightMm ?: layer?.lineWeightMm ?: 0.25f
            paint.strokeWidth = max(1.2f, lw * 4.2f)
            val lt = entity.lineType ?: layer?.lineType ?: CadLineType.CONTINUOUS
            paint.pathEffect = if (isThumbnail) null else lt.dashPatternPx?.let { DashPathEffect(it, 0f) }

            drawEntityOnCanvas(entity, doc, canvas, paint, textPaint, ::toX, ::toY, scale, invertWhiteLines)
        }

        if (drawTitleBlock) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = if (invertWhiteLines) Color.DKGRAY else Color.CYAN
            }
            canvas.drawRect(24f, 24f, widthPx - 24f, heightPx - 24f, borderPaint)
            canvas.drawRect(widthPx - 300f, heightPx - 76f, widthPx - 24f, heightPx - 24f, borderPaint)
            textPaint.color = borderPaint.color
            textPaint.textSize = 14f
            canvas.drawText("${doc.name} | ${doc.activeLayout.name}", widthPx - 288f, heightPx - 52f, textPaint)
            textPaint.textSize = 11f
            canvas.drawText("MOBILE CAD | Units: ${doc.units.shortLabel} | Entities: ${doc.entities.size}", widthPx - 288f, heightPx - 34f, textPaint)
        }
    }

    private fun drawEntityOnCanvas(
        entity: CadEntity,
        doc: CadDocument,
        canvas: Canvas,
        paint: Paint,
        textPaint: Paint,
        toX: (Double) -> Float,
        toY: (Double) -> Float,
        scale: Double,
        invertWhiteLines: Boolean
    ) {
        when (entity) {
            is CadLine -> canvas.drawLine(toX(entity.start.x), toY(entity.start.y), toX(entity.end.x), toY(entity.end.y), paint)
            is CadPolyline -> if (entity.points.size >= 2) {
                val path = Path()
                path.moveTo(toX(entity.points[0].x), toY(entity.points[0].y))
                for (i in 1 until entity.points.size) {
                    path.lineTo(toX(entity.points[i].x), toY(entity.points[i].y))
                }
                if (entity.isClosed) path.close()
                canvas.drawPath(path, paint)
            }
            is CadCircle -> canvas.drawCircle(toX(entity.center.x), toY(entity.center.y), (entity.radius * scale).toFloat(), paint)
            is CadArc -> {
                val r = (entity.radius * scale).toFloat()
                val cx = toX(entity.center.x)
                val cy = toY(entity.center.y)
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                val sweep = GeometryEngine.normalizeSweepDeg(entity.startAngleDeg, entity.endAngleDeg)
                canvas.drawArc(rect, -entity.startAngleDeg.toFloat(), -sweep.toFloat(), false, paint)
            }
            is CadEllipse -> {
                val pts = GeometryEngine.sampleEllipsePoints(entity, 48)
                if (pts.size >= 2) {
                    val path = Path()
                    path.moveTo(toX(pts[0].x), toY(pts[0].y))
                    for (i in 1 until pts.size) path.lineTo(toX(pts[i].x), toY(pts[i].y))
                    canvas.drawPath(path, paint)
                }
            }
            is CadSpline -> {
                val pts = GeometryEngine.sampleSplinePoints(entity, 10)
                if (pts.size >= 2) {
                    val path = Path()
                    path.moveTo(toX(pts[0].x), toY(pts[0].y))
                    for (i in 1 until pts.size) path.lineTo(toX(pts[i].x), toY(pts[i].y))
                    canvas.drawPath(path, paint)
                }
            }
            is CadPoint -> canvas.drawCircle(toX(entity.position.x), toY(entity.position.y), 3f, paint)
            is CadText -> {
                textPaint.color = paint.color
                textPaint.textSize = max(9f, (entity.height * scale).toFloat())
                canvas.save()
                canvas.translate(toX(entity.position.x), toY(entity.position.y))
                canvas.rotate(-entity.rotationDeg.toFloat())
                canvas.drawText(entity.text, 0f, 0f, textPaint)
                canvas.restore()
            }
            is CadBlockReference -> {
                val def = doc.blocks[entity.blockName] ?: return
                for (sub in GeometryEngine.explodeBlockReference(entity, def)) {
                    drawEntityOnCanvas(sub, doc, canvas, paint, textPaint, toX, toY, scale, invertWhiteLines)
                }
            }
            is CadDimension -> {
                val dimColor = if (invertWhiteLines) Color.rgb(0, 103, 130) else doc.dimensionStyle.lineColorArgb
                val origColor = paint.color
                paint.color = dimColor
                paint.strokeWidth = max(1f, paint.strokeWidth * 0.75f)
                canvas.drawLine(toX(entity.p1.x), toY(entity.p1.y), toX(entity.dimLinePoint.x), toY(entity.dimLinePoint.y), paint)
                canvas.drawLine(toX(entity.p2.x), toY(entity.p2.y), toX(entity.dimLinePoint.x), toY(entity.dimLinePoint.y), paint)
                val valNum = GeometryEngine.computeDimensionValue(entity, doc.dimensionStyle.scaleFactor)
                val label = entity.textOverride ?: formatDimText(entity.dimType, valNum, doc.dimensionStyle.decimalPrecision, doc.units.shortLabel)
                textPaint.color = if (invertWhiteLines) Color.rgb(178, 106, 0) else doc.dimensionStyle.textColorArgb
                textPaint.textSize = max(10f, (doc.dimensionStyle.textHeight * scale).toFloat().coerceAtMost(22f))
                canvas.drawText(label, toX(entity.dimLinePoint.x), toY(entity.dimLinePoint.y) - 4f, textPaint)
                paint.color = origColor
            }
            is CadRay, is CadXLine -> {
                val segs = GeometryEngine.entityToSegments(entity, doc.blocks)
                for ((a, b) in segs) {
                    canvas.drawLine(toX(a.x), toY(a.y), toX(b.x), toY(b.y), paint)
                }
            }
        }
    }

    fun formatDimText(type: DimensionType, value: Double, precision: Int, units: String): String {
        val pattern = "%.${precision.coerceIn(0, 4)}f"
        val numStr = String.format(Locale.US, pattern, value)
        return when (type) {
            DimensionType.ANGULAR -> "$numStr°"
            DimensionType.RADIUS -> "R$numStr $units"
            DimensionType.DIAMETER -> "Ø$numStr $units"
            DimensionType.ARC_LENGTH -> "⌒$numStr $units"
            else -> "$numStr $units"
        }
    }
}

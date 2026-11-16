package com.example.cad.io

import com.example.cad.geometry.GeometryEngine
import com.example.cad.model.AciColors
import com.example.cad.model.Bounds2D
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.DimensionType
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object DxfWriter {

    fun write(doc: CadDocument, outputStream: OutputStream, precomputedBounds: Bounds2D? = null) {
        val w = BufferedWriter(OutputStreamWriter(outputStream), 65536)
        val bounds = precomputedBounds ?: GeometryEngine.boundsOfAll(doc.entities, doc.blocks)

        fun pairStr(code: Int, value: String) {
            w.write(code.toString())
            w.write('\n'.code)
            w.write(value)
            w.write('\n'.code)
        }

        fun pairInt(code: Int, value: Int) {
            w.write(code.toString())
            w.write('\n'.code)
            w.write(value.toString())
            w.write('\n'.code)
        }

        fun fmt(d: Double): String {
            if (!d.isFinite()) return "0.000000"
            val ad = abs(d)
            if (ad > 1e11) return String.format(Locale.US, "%.6f", d)
            val scaled = (d * 1_000_000.0).roundToLong()
            if (scaled == 0L) return "0.000000"
            val neg = scaled < 0L
            val u = if (neg) -scaled else scaled
            val intPart = u / 1_000_000L
            val fracPart = (u % 1_000_000L).toInt()
            val sb = StringBuilder(20)
            if (neg) sb.append('-')
            sb.append(intPart)
            sb.append('.')
            var div = 100_000
            while (div > 0) {
                sb.append(('0'.code + ((fracPart / div) % 10)).toChar())
                div /= 10
            }
            return sb.toString()
        }

        // HEADER
        pairStr(0, "SECTION")
        pairStr(2, "HEADER")
        pairStr(9, "\$ACADVER")
        pairStr(1, "AC1024") // AutoCAD 2010 DXF
        pairStr(9, "\$INSUNITS")
        pairInt(70, doc.units.dxfCode)
        pairStr(9, "\$EXTMIN")
        pairStr(10, fmt(bounds.minX))
        pairStr(20, fmt(bounds.minY))
        pairStr(30, "0.0")
        pairStr(9, "\$EXTMAX")
        pairStr(10, fmt(bounds.maxX))
        pairStr(20, fmt(bounds.maxY))
        pairStr(30, "0.0")
        pairStr(0, "ENDSEC")

        // TABLES
        pairStr(0, "SECTION")
        pairStr(2, "TABLES")
        pairStr(0, "TABLE")
        pairStr(2, "LAYER")
        pairInt(70, doc.layers.size)
        for (layer in doc.layers) {
            pairStr(0, "LAYER")
            pairStr(2, layer.name)
            var flags = 0
            if (layer.isFrozen) flags = flags or 1
            if (layer.isLocked) flags = flags or 4
            pairInt(70, flags)
            val aci = if (layer.isOn) layer.aciColor else -layer.aciColor
            pairInt(62, aci)
            pairStr(6, layer.lineType.dxfName)
            pairInt(370, (layer.lineWeightMm * 100).toInt())
        }
        pairStr(0, "ENDTAB")
        pairStr(0, "ENDSEC")

        // BLOCKS
        pairStr(0, "SECTION")
        pairStr(2, "BLOCKS")
        for ((name, block) in doc.blocks) {
            pairStr(0, "BLOCK")
            pairStr(8, "0")
            pairStr(2, name)
            pairInt(70, 0)
            pairStr(10, fmt(block.basePoint.x))
            pairStr(20, fmt(block.basePoint.y))
            pairStr(30, "0.0")
            for (sub in block.entities) {
                writeEntity(sub, ::pairStr, ::pairInt, ::fmt)
            }
            pairStr(0, "ENDBLK")
            pairStr(8, "0")
        }
        pairStr(0, "ENDSEC")

        // ENTITIES
        pairStr(0, "SECTION")
        pairStr(2, "ENTITIES")
        for (entity in doc.entities) {
            writeEntity(entity, ::pairStr, ::pairInt, ::fmt)
        }
        pairStr(0, "ENDSEC")
        pairStr(0, "EOF")
        w.flush()
    }

    private fun writeEntity(
        entity: CadEntity,
        pairStr: (Int, String) -> Unit,
        pairInt: (Int, Int) -> Unit,
        fmt: (Double) -> String
    ) {
        fun writeCommon(type: String) {
            pairStr(0, type)
            pairStr(8, entity.layerName)
            entity.colorArgb?.let { pairInt(62, AciColors.nearestAci(it)) }
            entity.lineType?.let { pairStr(6, it.dxfName) }
            entity.lineWeightMm?.let { pairInt(370, (it * 100).toInt()) }
        }

        when (entity) {
            is CadLine -> {
                writeCommon("LINE")
                pairStr(10, fmt(entity.start.x)); pairStr(20, fmt(entity.start.y)); pairStr(30, "0.0")
                pairStr(11, fmt(entity.end.x)); pairStr(21, fmt(entity.end.y)); pairStr(31, "0.0")
            }
            is CadPolyline -> {
                writeCommon("LWPOLYLINE")
                pairInt(90, entity.points.size)
                pairInt(70, if (entity.isClosed) 1 else 0)
                for (pt in entity.points) {
                    pairStr(10, fmt(pt.x))
                    pairStr(20, fmt(pt.y))
                }
            }
            is CadCircle -> {
                writeCommon("CIRCLE")
                pairStr(10, fmt(entity.center.x)); pairStr(20, fmt(entity.center.y)); pairStr(30, "0.0")
                pairStr(40, fmt(entity.radius))
            }
            is CadArc -> {
                writeCommon("ARC")
                pairStr(10, fmt(entity.center.x)); pairStr(20, fmt(entity.center.y)); pairStr(30, "0.0")
                pairStr(40, fmt(entity.radius))
                pairStr(50, fmt(entity.startAngleDeg))
                pairStr(51, fmt(entity.endAngleDeg))
            }
            is CadEllipse -> {
                writeCommon("ELLIPSE")
                pairStr(10, fmt(entity.center.x)); pairStr(20, fmt(entity.center.y)); pairStr(30, "0.0")
                pairStr(11, fmt(entity.majorAxisEndOffset.x)); pairStr(21, fmt(entity.majorAxisEndOffset.y)); pairStr(31, "0.0")
                pairStr(40, fmt(entity.minorToMajorRatio))
                pairStr(41, fmt(entity.startParamRad))
                pairStr(42, fmt(entity.endParamRad))
            }
            is CadSpline -> {
                writeCommon("SPLINE")
                pairInt(70, if (entity.isClosed) 1 else 8)
                pairInt(71, entity.degree)
                pairInt(73, entity.controlPoints.size)
                for (pt in entity.controlPoints) {
                    pairStr(10, fmt(pt.x))
                    pairStr(20, fmt(pt.y))
                    pairStr(30, "0.0")
                }
            }
            is CadPoint -> {
                writeCommon("POINT")
                pairStr(10, fmt(entity.position.x)); pairStr(20, fmt(entity.position.y)); pairStr(30, "0.0")
            }
            is CadRay -> {
                writeCommon("RAY")
                pairStr(10, fmt(entity.origin.x)); pairStr(20, fmt(entity.origin.y)); pairStr(30, "0.0")
                pairStr(11, fmt(entity.direction.x)); pairStr(21, fmt(entity.direction.y)); pairStr(31, "0.0")
            }
            is CadXLine -> {
                writeCommon("XLINE")
                pairStr(10, fmt(entity.origin.x)); pairStr(20, fmt(entity.origin.y)); pairStr(30, "0.0")
                pairStr(11, fmt(entity.direction.x)); pairStr(21, fmt(entity.direction.y)); pairStr(31, "0.0")
            }
            is CadText -> {
                writeCommon("TEXT")
                pairStr(10, fmt(entity.position.x)); pairStr(20, fmt(entity.position.y)); pairStr(30, "0.0")
                pairStr(40, fmt(entity.height))
                pairStr(1, entity.text)
                pairStr(50, fmt(entity.rotationDeg))
            }
            is CadBlockReference -> {
                writeCommon("INSERT")
                pairStr(2, entity.blockName)
                pairStr(10, fmt(entity.insertionPoint.x)); pairStr(20, fmt(entity.insertionPoint.y)); pairStr(30, "0.0")
                pairStr(41, fmt(entity.scaleX)); pairStr(42, fmt(entity.scaleY)); pairStr(43, "1.0")
                pairStr(50, fmt(entity.rotationDeg))
            }
            is CadDimension -> {
                writeCommon("DIMENSION")
                val code = when (entity.dimType) {
                    DimensionType.ALIGNED -> 1
                    DimensionType.ANGULAR -> 2
                    DimensionType.DIAMETER -> 3
                    DimensionType.RADIUS -> 4
                    else -> 0
                }
                pairStr(10, fmt(entity.dimLinePoint.x)); pairStr(20, fmt(entity.dimLinePoint.y)); pairStr(30, "0.0")
                val mid = (entity.p1 + entity.p2) * 0.5
                pairStr(11, fmt(mid.x)); pairStr(21, fmt(mid.y)); pairStr(31, "0.0")
                pairInt(70, code)
                pairStr(1, entity.textOverride ?: "<>")
                pairStr(13, fmt(entity.p1.x)); pairStr(23, fmt(entity.p1.y)); pairStr(33, "0.0")
                pairStr(14, fmt(entity.p2.x)); pairStr(24, fmt(entity.p2.y)); pairStr(34, "0.0")
            }
        }
    }
}

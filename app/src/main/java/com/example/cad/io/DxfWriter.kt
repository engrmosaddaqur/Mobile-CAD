package com.example.cad.io

import com.example.cad.geometry.GeometryEngine
import com.example.cad.model.AciColors
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.DimensionType
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale

object DxfWriter {

    fun write(doc: CadDocument, outputStream: OutputStream) {
        val w = BufferedWriter(OutputStreamWriter(outputStream))
        val bounds = GeometryEngine.boundsOfAll(doc.entities, doc.blocks)

        fun pair(code: Int, value: Any) {
            w.write(code.toString())
            w.newLine()
            w.write(value.toString())
            w.newLine()
        }

        fun fmt(d: Double): String = String.format(Locale.US, "%.6f", d)

        // HEADER
        pair(0, "SECTION")
        pair(2, "HEADER")
        pair(9, "\$ACADVER")
        pair(1, "AC1024") // AutoCAD 2010 DXF
        pair(9, "\$INSUNITS")
        pair(70, doc.units.dxfCode)
        pair(9, "\$EXTMIN")
        pair(10, fmt(bounds.minX))
        pair(20, fmt(bounds.minY))
        pair(30, "0.0")
        pair(9, "\$EXTMAX")
        pair(10, fmt(bounds.maxX))
        pair(20, fmt(bounds.maxY))
        pair(30, "0.0")
        pair(0, "ENDSEC")

        // TABLES
        pair(0, "SECTION")
        pair(2, "TABLES")
        pair(0, "TABLE")
        pair(2, "LAYER")
        pair(70, doc.layers.size)
        for (layer in doc.layers) {
            pair(0, "LAYER")
            pair(2, layer.name)
            var flags = 0
            if (layer.isFrozen) flags = flags or 1
            if (layer.isLocked) flags = flags or 4
            pair(70, flags)
            val aci = if (layer.isOn) layer.aciColor else -layer.aciColor
            pair(62, aci)
            pair(6, layer.lineType.dxfName)
            pair(370, (layer.lineWeightMm * 100).toInt())
        }
        pair(0, "ENDTAB")
        pair(0, "ENDSEC")

        // BLOCKS
        pair(0, "SECTION")
        pair(2, "BLOCKS")
        for ((name, block) in doc.blocks) {
            pair(0, "BLOCK")
            pair(8, "0")
            pair(2, name)
            pair(70, 0)
            pair(10, fmt(block.basePoint.x))
            pair(20, fmt(block.basePoint.y))
            pair(30, "0.0")
            for (sub in block.entities) {
                writeEntity(sub, doc, ::pair, ::fmt)
            }
            pair(0, "ENDBLK")
            pair(8, "0")
        }
        pair(0, "ENDSEC")

        // ENTITIES
        pair(0, "SECTION")
        pair(2, "ENTITIES")
        for (entity in doc.entities) {
            writeEntity(entity, doc, ::pair, ::fmt)
        }
        pair(0, "ENDSEC")
        pair(0, "EOF")
        w.flush()
    }

    private fun writeEntity(
        entity: CadEntity,
        doc: CadDocument,
        pair: (Int, Any) -> Unit,
        fmt: (Double) -> String
    ) {
        fun writeCommon(type: String) {
            pair(0, type)
            pair(8, entity.layerName)
            entity.colorArgb?.let { pair(62, AciColors.nearestAci(it)) }
            entity.lineType?.let { pair(6, it.dxfName) }
            entity.lineWeightMm?.let { pair(370, (it * 100).toInt()) }
        }

        when (entity) {
            is CadLine -> {
                writeCommon("LINE")
                pair(10, fmt(entity.start.x)); pair(20, fmt(entity.start.y)); pair(30, "0.0")
                pair(11, fmt(entity.end.x)); pair(21, fmt(entity.end.y)); pair(31, "0.0")
            }
            is CadPolyline -> {
                writeCommon("LWPOLYLINE")
                pair(90, entity.points.size)
                pair(70, if (entity.isClosed) 1 else 0)
                for (pt in entity.points) {
                    pair(10, fmt(pt.x))
                    pair(20, fmt(pt.y))
                }
            }
            is CadCircle -> {
                writeCommon("CIRCLE")
                pair(10, fmt(entity.center.x)); pair(20, fmt(entity.center.y)); pair(30, "0.0")
                pair(40, fmt(entity.radius))
            }
            is CadArc -> {
                writeCommon("ARC")
                pair(10, fmt(entity.center.x)); pair(20, fmt(entity.center.y)); pair(30, "0.0")
                pair(40, fmt(entity.radius))
                pair(50, fmt(entity.startAngleDeg))
                pair(51, fmt(entity.endAngleDeg))
            }
            is CadEllipse -> {
                writeCommon("ELLIPSE")
                pair(10, fmt(entity.center.x)); pair(20, fmt(entity.center.y)); pair(30, "0.0")
                pair(11, fmt(entity.majorAxisEndOffset.x)); pair(21, fmt(entity.majorAxisEndOffset.y)); pair(31, "0.0")
                pair(40, fmt(entity.minorToMajorRatio))
                pair(41, fmt(entity.startParamRad))
                pair(42, fmt(entity.endParamRad))
            }
            is CadSpline -> {
                writeCommon("SPLINE")
                pair(70, if (entity.isClosed) 1 else 8)
                pair(71, entity.degree)
                pair(73, entity.controlPoints.size)
                for (pt in entity.controlPoints) {
                    pair(10, fmt(pt.x))
                    pair(20, fmt(pt.y))
                    pair(30, "0.0")
                }
            }
            is CadPoint -> {
                writeCommon("POINT")
                pair(10, fmt(entity.position.x)); pair(20, fmt(entity.position.y)); pair(30, "0.0")
            }
            is CadRay -> {
                writeCommon("RAY")
                pair(10, fmt(entity.origin.x)); pair(20, fmt(entity.origin.y)); pair(30, "0.0")
                pair(11, fmt(entity.direction.x)); pair(21, fmt(entity.direction.y)); pair(31, "0.0")
            }
            is CadXLine -> {
                writeCommon("XLINE")
                pair(10, fmt(entity.origin.x)); pair(20, fmt(entity.origin.y)); pair(30, "0.0")
                pair(11, fmt(entity.direction.x)); pair(21, fmt(entity.direction.y)); pair(31, "0.0")
            }
            is CadText -> {
                writeCommon("TEXT")
                pair(10, fmt(entity.position.x)); pair(20, fmt(entity.position.y)); pair(30, "0.0")
                pair(40, fmt(entity.height))
                pair(1, entity.text)
                pair(50, fmt(entity.rotationDeg))
            }
            is CadBlockReference -> {
                writeCommon("INSERT")
                pair(2, entity.blockName)
                pair(10, fmt(entity.insertionPoint.x)); pair(20, fmt(entity.insertionPoint.y)); pair(30, "0.0")
                pair(41, fmt(entity.scaleX)); pair(42, fmt(entity.scaleY)); pair(43, "1.0")
                pair(50, fmt(entity.rotationDeg))
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
                pair(10, fmt(entity.dimLinePoint.x)); pair(20, fmt(entity.dimLinePoint.y)); pair(30, "0.0")
                val mid = (entity.p1 + entity.p2) * 0.5
                pair(11, fmt(mid.x)); pair(21, fmt(mid.y)); pair(31, "0.0")
                pair(70, code)
                pair(1, entity.textOverride ?: "<>")
                pair(13, fmt(entity.p1.x)); pair(23, fmt(entity.p1.y)); pair(33, "0.0")
                pair(14, fmt(entity.p2.x)); pair(24, fmt(entity.p2.y)); pair(34, "0.0")
            }
        }
    }
}

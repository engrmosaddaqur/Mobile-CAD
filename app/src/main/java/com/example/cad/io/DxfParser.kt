package com.example.cad.io

import com.example.cad.model.AciColors
import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.CadSourceFormat
import com.example.cad.model.CadUnits
import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.PI

object DxfParser {

    fun parse(inputStream: InputStream, drawingName: String, sourceUri: String? = null): CadDocument {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var section = ""
        var inTables = false
        var currentTable = ""
        var currentBlockName: String? = null
        var currentBlockBase = Vec2.ZERO
        val blockEntities = mutableListOf<CadEntity>()

        var units = CadUnits.MILLIMETERS
        val layers = mutableMapOf<String, CadLayer>()
        val blocks = mutableMapOf<String, CadBlockDefinition>()
        val entities = mutableListOf<CadEntity>()
        val unsupportedCounts = mutableMapOf<String, Int>()

        var headerVar = ""
        var polylineInProgress: MutableList<Vec2>? = null
        var polylineLayer = "0"
        var polylineClosed = false
        var polylineColor: Int? = null

        fun readPair(): Pair<Int, String>? {
            val codeLine = reader.readLine() ?: return null
            val valLine = reader.readLine() ?: return null
            val code = codeLine.trim().toIntOrNull() ?: return null
            return code to valLine.trimEnd('\r', '\n')
        }

        // Collect all pairs per entity after seeing group code 0
        var pendingPair: Pair<Int, String>? = readPair()
        while (pendingPair != null) {
            val (code, value) = pendingPair
            if (code == 0 && value.trim().equals("EOF", ignoreCase = true)) break

            if (code == 0 && value.trim().equals("SECTION", ignoreCase = true)) {
                val next = readPair()
                section = if (next?.first == 2) next.second.trim().uppercase() else ""
                inTables = (section == "TABLES")
                pendingPair = readPair()
                continue
            }
            if (code == 0 && value.trim().equals("ENDSEC", ignoreCase = true)) {
                section = ""
                inTables = false
                currentTable = ""
                pendingPair = readPair()
                continue
            }

            when (section) {
                "HEADER" -> {
                    if (code == 9) {
                        headerVar = value.trim().uppercase()
                    } else if (headerVar == "\$INSUNITS" && code == 70) {
                        units = CadUnits.fromDxfCode(value.trim().toIntOrNull() ?: 4)
                    }
                    pendingPair = readPair()
                }

                "TABLES" -> {
                    if (code == 0 && value.trim().equals("TABLE", ignoreCase = true)) {
                        val next = readPair()
                        currentTable = if (next?.first == 2) next.second.trim().uppercase() else ""
                        pendingPair = readPair()
                    } else if (code == 0 && value.trim().equals("ENDTAB", ignoreCase = true)) {
                        currentTable = ""
                        pendingPair = readPair()
                    } else if (code == 0 && value.trim().equals("LAYER", ignoreCase = true) && currentTable == "LAYER") {
                        val props = mutableListOf<Pair<Int, String>>()
                        var p = readPair()
                        while (p != null && p.first != 0) {
                            props.add(p)
                            p = readPair()
                        }
                        parseLayer(props)?.let { layers[it.name] = it }
                        pendingPair = p
                    } else {
                        pendingPair = readPair()
                    }
                }

                "BLOCKS" -> {
                    if (code == 0 && value.trim().equals("BLOCK", ignoreCase = true)) {
                        val props = mutableListOf<Pair<Int, String>>()
                        var p = readPair()
                        while (p != null && p.first != 0) {
                            props.add(p)
                            p = readPair()
                        }
                        currentBlockName = props.firstOrNull { it.first == 2 }?.second?.trim()
                        val bx = props.firstOrNull { it.first == 10 }?.second?.trim()?.toDoubleOrNull() ?: 0.0
                        val by = props.firstOrNull { it.first == 20 }?.second?.trim()?.toDoubleOrNull() ?: 0.0
                        currentBlockBase = Vec2(bx, by)
                        blockEntities.clear()
                        pendingPair = p
                    } else if (code == 0 && value.trim().equals("ENDBLK", ignoreCase = true)) {
                        currentBlockName?.let { bName ->
                            if (!bName.startsWith("*")) {
                                blocks[bName] = CadBlockDefinition(bName, currentBlockBase, blockEntities.toList())
                            }
                        }
                        currentBlockName = null
                        blockEntities.clear()
                        pendingPair = readPair()
                    } else if (code == 0 && currentBlockName != null) {
                        val entityType = value.trim().uppercase()
                        val props = mutableListOf<Pair<Int, String>>()
                        var p = readPair()
                        while (p != null && p.first != 0) {
                            props.add(p)
                            p = readPair()
                        }
                        val parsed = parseEntity(entityType, props, unsupportedCounts)
                        if (parsed != null) blockEntities.add(parsed)
                        pendingPair = p
                    } else {
                        pendingPair = readPair()
                    }
                }

                "ENTITIES" -> {
                    if (code == 0) {
                        val entityType = value.trim().uppercase()
                        val props = mutableListOf<Pair<Int, String>>()
                        var p = readPair()
                        while (p != null && p.first != 0) {
                            props.add(p)
                            p = readPair()
                        }
                        when (entityType) {
                            "POLYLINE" -> {
                                polylineInProgress = mutableListOf()
                                polylineLayer = props.firstOrNull { it.first == 8 }?.second?.trim().orEmpty().ifEmpty { "0" }
                                val flags = props.firstOrNull { it.first == 70 }?.second?.trim()?.toIntOrNull() ?: 0
                                polylineClosed = (flags and 1) != 0
                                val aci = props.firstOrNull { it.first == 62 }?.second?.trim()?.toIntOrNull()
                                polylineColor = aci?.let { if (it in 1..255) AciColors.toArgb(it) else null }
                            }
                            "VERTEX" -> {
                                val vx = props.firstOrNull { it.first == 10 }?.second?.trim()?.toDoubleOrNull() ?: 0.0
                                val vy = props.firstOrNull { it.first == 20 }?.second?.trim()?.toDoubleOrNull() ?: 0.0
                                polylineInProgress?.add(Vec2(vx, vy))
                            }
                            "SEQEND" -> {
                                polylineInProgress?.let { pts ->
                                    if (pts.size >= 2) {
                                        entities.add(
                                            CadPolyline(
                                                layerName = polylineLayer,
                                                colorArgb = polylineColor,
                                                points = pts.toList(),
                                                isClosed = polylineClosed
                                            )
                                        )
                                    }
                                }
                                polylineInProgress = null
                            }
                            else -> {
                                val parsed = parseEntity(entityType, props, unsupportedCounts)
                                if (parsed != null) entities.add(parsed)
                            }
                        }
                        pendingPair = p
                    } else {
                        pendingPair = readPair()
                    }
                }

                else -> {
                    pendingPair = readPair()
                }
            }
        }

        // Ensure every referenced layer exists
        val mergedLayers = CadDocument.defaultLayers().associateBy { it.name }.toMutableMap()
        mergedLayers.putAll(layers)
        for (entity in entities) {
            if (!mergedLayers.containsKey(entity.layerName)) {
                mergedLayers[entity.layerName] = CadLayer(entity.layerName)
            }
        }

        val warnings = if (unsupportedCounts.isNotEmpty()) {
            listOf(
                "Skipped unsupported DXF entities: " +
                    unsupportedCounts.entries.joinToString(", ") { "${it.key} (${it.value})" }
            )
        } else emptyList()

        return CadDocument(
            name = drawingName,
            units = units,
            layers = mergedLayers.values.toList(),
            currentLayerName = mergedLayers.keys.firstOrNull() ?: "0",
            blocks = blocks,
            entities = entities,
            sourceFormat = CadSourceFormat.DXF,
            sourceUri = sourceUri,
            importWarnings = warnings
        )
    }

    private fun parseLayer(props: List<Pair<Int, String>>): CadLayer? {
        val name = props.firstOrNull { it.first == 2 }?.second?.trim().orEmpty()
        if (name.isEmpty()) return null
        val flags = props.firstOrNull { it.first == 70 }?.second?.trim()?.toIntOrNull() ?: 0
        val rawColor = props.firstOrNull { it.first == 62 }?.second?.trim()?.toIntOrNull() ?: 7
        val isOn = rawColor >= 0
        val aci = kotlin.math.abs(rawColor).coerceIn(1, 255)
        val ltypeName = props.firstOrNull { it.first == 6 }?.second?.trim() ?: "CONTINUOUS"
        val lwRaw = props.firstOrNull { it.first == 370 }?.second?.trim()?.toIntOrNull() ?: 25
        val lwMm = if (lwRaw > 0) lwRaw / 100f else 0.25f

        return CadLayer(
            name = name,
            colorArgb = AciColors.toArgb(aci),
            aciColor = aci,
            lineType = CadLineType.fromDxfName(ltypeName),
            lineWeightMm = lwMm,
            isOn = isOn,
            isFrozen = (flags and 1) != 0,
            isLocked = (flags and 4) != 0
        )
    }

    private fun parseEntity(
        type: String,
        props: List<Pair<Int, String>>,
        unsupportedCounts: MutableMap<String, Int>
    ): CadEntity? {
        val layer = props.firstOrNull { it.first == 8 }?.second?.trim().orEmpty().ifEmpty { "0" }
        val aci = props.firstOrNull { it.first == 62 }?.second?.trim()?.toIntOrNull()
        val colorArgb = if (aci != null && aci in 1..255) AciColors.toArgb(aci) else null
        val ltypeStr = props.firstOrNull { it.first == 6 }?.second?.trim()
        val lineType = ltypeStr?.let { CadLineType.fromDxfName(it) }
        val lwRaw = props.firstOrNull { it.first == 370 }?.second?.trim()?.toIntOrNull()
        val lineWeight = if (lwRaw != null && lwRaw > 0) lwRaw / 100f else null

        fun d(code: Int, default: Double = 0.0): Double =
            props.firstOrNull { it.first == code }?.second?.trim()?.toDoubleOrNull() ?: default

        fun s(code: Int, default: String = ""): String =
            props.firstOrNull { it.first == code }?.second?.trim() ?: default

        fun i(code: Int, default: Int = 0): Int =
            props.firstOrNull { it.first == code }?.second?.trim()?.toIntOrNull() ?: default

        return when (type) {
            "LINE" -> CadLine(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                start = Vec2(d(10), d(20)),
                end = Vec2(d(11), d(21))
            )

            "LWPOLYLINE" -> {
                val pts = mutableListOf<Vec2>()
                var pendingX: Double? = null
                for ((c, v) in props) {
                    if (c == 10) pendingX = v.trim().toDoubleOrNull()
                    else if (c == 20 && pendingX != null) {
                        val y = v.trim().toDoubleOrNull() ?: 0.0
                        pts.add(Vec2(pendingX, y))
                        pendingX = null
                    }
                }
                if (pts.size < 2) return null
                CadPolyline(
                    layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                    points = pts,
                    isClosed = (i(70) and 1) != 0
                )
            }

            "CIRCLE" -> CadCircle(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                center = Vec2(d(10), d(20)),
                radius = d(40, 10.0).coerceAtLeast(1e-4)
            )

            "ARC" -> CadArc(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                center = Vec2(d(10), d(20)),
                radius = d(40, 10.0).coerceAtLeast(1e-4),
                startAngleDeg = d(50, 0.0),
                endAngleDeg = d(51, 90.0)
            )

            "ELLIPSE" -> CadEllipse(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                center = Vec2(d(10), d(20)),
                majorAxisEndOffset = Vec2(d(11, 10.0), d(21, 0.0)),
                minorToMajorRatio = d(40, 0.5).coerceIn(0.05, 1.0),
                startParamRad = d(41, 0.0),
                endParamRad = d(42, 2.0 * PI)
            )

            "SPLINE" -> {
                val ctrlPts = mutableListOf<Vec2>()
                var pendingX: Double? = null
                for ((c, v) in props) {
                    if (c == 10) pendingX = v.trim().toDoubleOrNull()
                    else if (c == 20 && pendingX != null) {
                        ctrlPts.add(Vec2(pendingX, v.trim().toDoubleOrNull() ?: 0.0))
                        pendingX = null
                    }
                }
                if (ctrlPts.size < 2) return null
                CadSpline(
                    layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                    controlPoints = ctrlPts,
                    degree = i(71, 3),
                    isClosed = (i(70) and 1) != 0
                )
            }

            "POINT" -> CadPoint(
                layerName = layer, colorArgb = colorArgb,
                position = Vec2(d(10), d(20))
            )

            "RAY" -> CadRay(
                layerName = layer, colorArgb = colorArgb, lineType = lineType,
                origin = Vec2(d(10), d(20)),
                direction = Vec2(d(11, 1.0), d(21, 0.0))
            )

            "XLINE" -> CadXLine(
                layerName = layer, colorArgb = colorArgb, lineType = lineType,
                origin = Vec2(d(10), d(20)),
                direction = Vec2(d(11, 1.0), d(21, 0.0))
            )

            "TEXT", "MTEXT" -> {
                val rawText = s(1, "").replace("\\P", "\n").replace(Regex("\\{[^}]*;|\\}"), "")
                if (rawText.isEmpty()) return null
                CadText(
                    layerName = layer, colorArgb = colorArgb,
                    position = Vec2(d(10), d(20)),
                    text = rawText,
                    height = d(40, 4.0).coerceAtLeast(0.5),
                    rotationDeg = d(50, 0.0)
                )
            }

            "INSERT" -> {
                val bName = s(2, "")
                if (bName.isEmpty()) return null
                CadBlockReference(
                    layerName = layer, colorArgb = colorArgb,
                    blockName = bName,
                    insertionPoint = Vec2(d(10), d(20)),
                    scaleX = d(41, 1.0),
                    scaleY = d(42, 1.0),
                    rotationDeg = d(50, 0.0)
                )
            }

            "DIMENSION" -> {
                val dimFlags = i(70, 0) and 0x0F
                val dimType = when (dimFlags) {
                    1 -> DimensionType.ALIGNED
                    2, 5 -> DimensionType.ANGULAR
                    3 -> DimensionType.DIAMETER
                    4 -> DimensionType.RADIUS
                    else -> DimensionType.LINEAR
                }
                val defPt = Vec2(d(10), d(20))
                val p1 = if (props.any { it.first == 13 }) Vec2(d(13), d(23)) else defPt
                val p2 = if (props.any { it.first == 14 }) Vec2(d(14), d(24)) else Vec2(d(11), d(21))
                val override = s(1, "").takeIf { it.isNotEmpty() && it != "<>" }
                CadDimension(
                    layerName = layer, colorArgb = colorArgb,
                    dimType = dimType,
                    p1 = p1,
                    p2 = p2,
                    dimLinePoint = defPt,
                    textOverride = override
                )
            }

            "SOLID", "3DFACE" -> {
                val pts = listOf(
                    Vec2(d(10), d(20)),
                    Vec2(d(11), d(21)),
                    Vec2(d(12), d(22)),
                    Vec2(d(13, d(12)), d(23, d(22)))
                )
                CadPolyline(layerName = layer, colorArgb = colorArgb, points = pts, isClosed = true)
            }

            else -> {
                unsupportedCounts[type] = (unsupportedCounts[type] ?: 0) + 1
                null
            }
        }
    }
}

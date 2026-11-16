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

    private val MTEXT_BRACE_REGEX = Regex("\\{[^}]*;|\\}")

    /** Reusable zero-Pair-allocation property buffer for each DXF record */
    private class PropBag {
        var codes = IntArray(64)
        var values = ArrayList<String>(64)
        var size = 0

        fun clear() {
            size = 0
            values.clear()
        }

        fun add(code: Int, value: String) {
            if (size >= codes.size) {
                codes = codes.copyOf(codes.size * 2)
            }
            codes[size] = code
            values.add(value)
            size++
        }

        fun find(code: Int): String? {
            for (i in 0 until size) {
                if (codes[i] == code) return values[i]
            }
            return null
        }

        fun has(code: Int): Boolean {
            for (i in 0 until size) {
                if (codes[i] == code) return true
            }
            return false
        }

        fun d(code: Int, default: Double = 0.0): Double {
            val v = find(code)?.trim()?.toDoubleOrNull() ?: return default
            return if (v.isFinite()) v else default
        }

        fun s(code: Int, default: String = ""): String =
            find(code)?.trim() ?: default

        fun i(code: Int, default: Int = 0): Int =
            find(code)?.trim()?.toIntOrNull() ?: default
    }

    fun parse(inputStream: InputStream, drawingName: String, sourceUri: String? = null): CadDocument {
        val reader = BufferedReader(InputStreamReader(inputStream), 65536)
        var section = ""
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
        var firstLine = true

        var curCode = 0
        var curValue = ""
        val bag = PropBag()

        fun readNextPair(): Boolean {
            var codeLine = reader.readLine() ?: return false
            if (firstLine) {
                firstLine = false
                if (codeLine.isNotEmpty() && codeLine[0] == '\uFEFF') {
                    codeLine = codeLine.substring(1)
                }
            }
            while (codeLine.isBlank()) {
                codeLine = reader.readLine() ?: return false
            }
            val valLine = reader.readLine() ?: return false
            val parsedCode = codeLine.trim().toIntOrNull() ?: return false
            curCode = parsedCode
            curValue = valLine.trimEnd('\r', '\n')
            return true
        }

        fun collectEntityPropsIntoBag(): Boolean {
            bag.clear()
            while (true) {
                if (!readNextPair()) return false
                if (curCode == 0) return true
                bag.add(curCode, curValue)
            }
        }

        var hasPending = readNextPair()
        while (hasPending) {
            val code = curCode
            val value = curValue
            if (code == 0 && value.trim().equals("EOF", ignoreCase = true)) break

            if (code == 0 && value.trim().equals("SECTION", ignoreCase = true)) {
                section = if (readNextPair() && curCode == 2) curValue.trim().uppercase() else ""
                hasPending = readNextPair()
                continue
            }
            if (code == 0 && value.trim().equals("ENDSEC", ignoreCase = true)) {
                section = ""
                currentTable = ""
                hasPending = readNextPair()
                continue
            }

            when (section) {
                "HEADER" -> {
                    if (code == 9) {
                        headerVar = value.trim().uppercase()
                    } else if (headerVar == "\$INSUNITS" && code == 70) {
                        units = CadUnits.fromDxfCode(value.trim().toIntOrNull() ?: 4)
                    }
                    hasPending = readNextPair()
                }

                "TABLES" -> {
                    if (code == 0 && value.trim().equals("TABLE", ignoreCase = true)) {
                        currentTable = if (readNextPair() && curCode == 2) curValue.trim().uppercase() else ""
                        hasPending = readNextPair()
                    } else if (code == 0 && value.trim().equals("ENDTAB", ignoreCase = true)) {
                        currentTable = ""
                        hasPending = readNextPair()
                    } else if (code == 0 && value.trim().equals("LAYER", ignoreCase = true) && currentTable == "LAYER") {
                        hasPending = collectEntityPropsIntoBag()
                        parseLayer(bag)?.let { layers[it.name] = it }
                    } else {
                        hasPending = readNextPair()
                    }
                }

                "BLOCKS" -> {
                    if (code == 0 && value.trim().equals("BLOCK", ignoreCase = true)) {
                        hasPending = collectEntityPropsIntoBag()
                        currentBlockName = bag.find(2)?.trim()
                        currentBlockBase = Vec2(bag.d(10), bag.d(20))
                        blockEntities.clear()
                    } else if (code == 0 && value.trim().equals("ENDBLK", ignoreCase = true)) {
                        currentBlockName?.let { bName ->
                            if (!bName.startsWith("*")) {
                                blocks[bName] = CadBlockDefinition(bName, currentBlockBase, blockEntities.toList())
                            }
                        }
                        currentBlockName = null
                        blockEntities.clear()
                        hasPending = readNextPair()
                    } else if (code == 0 && currentBlockName != null) {
                        val entityType = value.trim().uppercase()
                        hasPending = collectEntityPropsIntoBag()
                        val parsed = parseEntity(entityType, bag, unsupportedCounts)
                        if (parsed != null) blockEntities.add(parsed)
                    } else {
                        hasPending = readNextPair()
                    }
                }

                "ENTITIES" -> {
                    if (code == 0) {
                        val entityType = value.trim().uppercase()
                        hasPending = collectEntityPropsIntoBag()
                        when (entityType) {
                            "POLYLINE" -> {
                                polylineInProgress = mutableListOf()
                                polylineLayer = bag.s(8, "0").ifEmpty { "0" }
                                polylineClosed = (bag.i(70) and 1) != 0
                                val aci = bag.find(62)?.trim()?.toIntOrNull()
                                polylineColor = aci?.let { if (it in 1..255) AciColors.toArgb(it) else null }
                            }
                            "VERTEX" -> {
                                polylineInProgress?.add(Vec2(bag.d(10), bag.d(20)))
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
                                val parsed = parseEntity(entityType, bag, unsupportedCounts)
                                if (parsed != null) entities.add(parsed)
                            }
                        }
                    } else {
                        hasPending = readNextPair()
                    }
                }

                else -> {
                    hasPending = readNextPair()
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

    private fun parseLayer(bag: PropBag): CadLayer? {
        val name = bag.s(2)
        if (name.isEmpty()) return null
        val flags = bag.i(70, 0)
        val rawColor = bag.i(62, 7)
        val isOn = rawColor >= 0
        val aci = kotlin.math.abs(rawColor).coerceIn(1, 255)
        val ltypeName = bag.s(6, "CONTINUOUS").ifEmpty { "CONTINUOUS" }
        val lwRaw = bag.i(370, 25)
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
        bag: PropBag,
        unsupportedCounts: MutableMap<String, Int>
    ): CadEntity? {
        val layer = bag.s(8, "0").ifEmpty { "0" }
        val aci = bag.find(62)?.trim()?.toIntOrNull()
        val colorArgb = if (aci != null && aci in 1..255) AciColors.toArgb(aci) else null
        val ltypeStr = bag.find(6)?.trim()
        val lineType = ltypeStr?.let { CadLineType.fromDxfName(it) }
        val lwRaw = bag.find(370)?.trim()?.toIntOrNull()
        val lineWeight = if (lwRaw != null && lwRaw > 0) lwRaw / 100f else null

        return when (type) {
            "LINE" -> CadLine(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                start = Vec2(bag.d(10), bag.d(20)),
                end = Vec2(bag.d(11), bag.d(21))
            )

            "LWPOLYLINE" -> {
                val pts = ArrayList<Vec2>(maxOf(4, bag.size / 2))
                var pendingX: Double? = null
                for (idx in 0 until bag.size) {
                    val c = bag.codes[idx]
                    if (c == 10) pendingX = bag.values[idx].trim().toDoubleOrNull()
                    else if (c == 20 && pendingX != null) {
                        val y = bag.values[idx].trim().toDoubleOrNull() ?: 0.0
                        pts.add(Vec2(pendingX, y))
                        pendingX = null
                    }
                }
                if (pts.size < 2) return null
                CadPolyline(
                    layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                    points = pts,
                    isClosed = (bag.i(70) and 1) != 0
                )
            }

            "CIRCLE" -> CadCircle(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                center = Vec2(bag.d(10), bag.d(20)),
                radius = bag.d(40, 10.0).coerceAtLeast(1e-4)
            )

            "ARC" -> CadArc(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                center = Vec2(bag.d(10), bag.d(20)),
                radius = bag.d(40, 10.0).coerceAtLeast(1e-4),
                startAngleDeg = bag.d(50, 0.0),
                endAngleDeg = bag.d(51, 90.0)
            )

            "ELLIPSE" -> CadEllipse(
                layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                center = Vec2(bag.d(10), bag.d(20)),
                majorAxisEndOffset = Vec2(bag.d(11, 10.0), bag.d(21, 0.0)),
                minorToMajorRatio = bag.d(40, 0.5).coerceIn(0.05, 1.0),
                startParamRad = bag.d(41, 0.0),
                endParamRad = bag.d(42, 2.0 * PI)
            )

            "SPLINE" -> {
                val ctrlPts = ArrayList<Vec2>(maxOf(4, bag.size / 3))
                var pendingX: Double? = null
                for (idx in 0 until bag.size) {
                    val c = bag.codes[idx]
                    if (c == 10) pendingX = bag.values[idx].trim().toDoubleOrNull()
                    else if (c == 20 && pendingX != null) {
                        ctrlPts.add(Vec2(pendingX, bag.values[idx].trim().toDoubleOrNull() ?: 0.0))
                        pendingX = null
                    }
                }
                if (ctrlPts.size < 2) return null
                CadSpline(
                    layerName = layer, colorArgb = colorArgb, lineType = lineType, lineWeightMm = lineWeight,
                    controlPoints = ctrlPts,
                    degree = bag.i(71, 3),
                    isClosed = (bag.i(70) and 1) != 0
                )
            }

            "POINT" -> CadPoint(
                layerName = layer, colorArgb = colorArgb,
                position = Vec2(bag.d(10), bag.d(20))
            )

            "RAY" -> CadRay(
                layerName = layer, colorArgb = colorArgb, lineType = lineType,
                origin = Vec2(bag.d(10), bag.d(20)),
                direction = Vec2(bag.d(11, 1.0), bag.d(21, 0.0))
            )

            "XLINE" -> CadXLine(
                layerName = layer, colorArgb = colorArgb, lineType = lineType,
                origin = Vec2(bag.d(10), bag.d(20)),
                direction = Vec2(bag.d(11, 1.0), bag.d(21, 0.0))
            )

            "TEXT", "MTEXT" -> {
                val rawText = bag.s(1, "").replace("\\P", "\n").replace(MTEXT_BRACE_REGEX, "")
                if (rawText.isEmpty()) return null
                CadText(
                    layerName = layer, colorArgb = colorArgb,
                    position = Vec2(bag.d(10), bag.d(20)),
                    text = rawText,
                    height = bag.d(40, 4.0).coerceAtLeast(0.5),
                    rotationDeg = bag.d(50, 0.0)
                )
            }

            "INSERT" -> {
                val bName = bag.s(2, "")
                if (bName.isEmpty()) return null
                CadBlockReference(
                    layerName = layer, colorArgb = colorArgb,
                    blockName = bName,
                    insertionPoint = Vec2(bag.d(10), bag.d(20)),
                    scaleX = bag.d(41, 1.0),
                    scaleY = bag.d(42, 1.0),
                    rotationDeg = bag.d(50, 0.0)
                )
            }

            "DIMENSION" -> {
                val dimFlags = bag.i(70, 0) and 0x0F
                val dimType = when (dimFlags) {
                    1 -> DimensionType.ALIGNED
                    2, 5 -> DimensionType.ANGULAR
                    3 -> DimensionType.DIAMETER
                    4 -> DimensionType.RADIUS
                    else -> DimensionType.LINEAR
                }
                val defPt = Vec2(bag.d(10), bag.d(20))
                val p1 = if (bag.has(13)) Vec2(bag.d(13), bag.d(23)) else defPt
                val p2 = if (bag.has(14)) Vec2(bag.d(14), bag.d(24)) else Vec2(bag.d(11), bag.d(21))
                val override = bag.s(1, "").takeIf { it.isNotEmpty() && it != "<>" }
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
                    Vec2(bag.d(10), bag.d(20)),
                    Vec2(bag.d(11), bag.d(21)),
                    Vec2(bag.d(12), bag.d(22)),
                    Vec2(bag.d(13, bag.d(12)), bag.d(23, bag.d(22)))
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

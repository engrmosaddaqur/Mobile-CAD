package com.example.cad.io

import com.example.cad.model.AciColors
import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadSourceFormat
import com.example.cad.model.CadUnits
import com.example.cad.model.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DwgVersion(val code: String, val label: String) {
    R13("AC1012", "AutoCAD R13 DWG"),
    R14("AC1014", "AutoCAD R14 DWG"),
    R2000("AC1015", "AutoCAD 2000/2002 DWG"),
    R2004("AC1018", "AutoCAD 2004–2006 DWG"),
    R2007("AC1021", "AutoCAD 2007–2009 DWG"),
    R2010("AC1024", "AutoCAD 2010–2012 DWG"),
    R2013("AC1027", "AutoCAD 2013–2017 DWG"),
    R2018("AC1032", "AutoCAD 2018–2025 DWG");

    val isR2000Plus: Boolean
        get() = ordinal >= R2000.ordinal

    val isR2004Plus: Boolean
        get() = ordinal >= R2004.ordinal

    val isR2007Plus: Boolean
        get() = ordinal >= R2007.ordinal

    val isR2010Plus: Boolean
        get() = ordinal >= R2010.ordinal

    val isR2013Plus: Boolean
        get() = ordinal >= R2013.ordinal

    companion object {
        fun fromHeader(header6: String): DwgVersion? = entries.firstOrNull { it.code == header6 }
    }
}

/**
 * Pure-Kotlin Binary DWG Bitstream Reader following the
 * Open Design Specification for .dwg files (AC1012–AC1032).
 */
internal class DwgBitReader(
    private val data: ByteArray,
    startByteOffset: Int = 0
) {
    var bitPos: Long = startByteOffset.toLong() * 8L
        private set

    val totalBits: Long = data.size.toLong() * 8L

    fun seekBit(newBitPos: Long) {
        bitPos = newBitPos.coerceIn(0L, totalBits)
    }

    fun seekByte(byteOffset: Int) {
        seekBit(byteOffset.toLong() * 8L)
    }

    fun skipBits(count: Long) {
        seekBit(bitPos + count)
    }

    fun readBit(): Int {
        if (bitPos >= totalBits) return 0
        val byteIdx = (bitPos ushr 3).toInt()
        val bitInByte = 7 - (bitPos and 7L).toInt()
        bitPos++
        return (data[byteIdx].toInt() ushr bitInByte) and 1
    }

    fun readBits(count: Int): Int {
        var result = 0
        for (i in 0 until count) {
            result = (result shl 1) or readBit()
        }
        return result
    }

    fun readRawByte(): Int {
        if ((bitPos and 7L) == 0L) {
            val idx = (bitPos ushr 3).toInt()
            if (idx >= data.size) {
                bitPos = totalBits
                return 0
            }
            bitPos += 8L
            return data[idx].toInt() and 0xFF
        }
        return readBits(8)
    }

    fun readRawBytes(count: Int): ByteArray {
        val safeCount = count.coerceAtLeast(0).coerceAtMost(((totalBits - bitPos) ushr 3).toInt().coerceAtLeast(0))
        val out = ByteArray(safeCount)
        for (i in 0 until safeCount) {
            out[i] = readRawByte().toByte()
        }
        return out
    }

    fun readRawShortLE(): Int {
        val b0 = readRawByte()
        val b1 = readRawByte()
        return (b1 shl 8) or b0
    }

    fun readRawSignedShortLE(): Int = readRawShortLE().toShort().toInt()

    fun readRawIntLE(): Int {
        val b0 = readRawByte()
        val b1 = readRawByte()
        val b2 = readRawByte()
        val b3 = readRawByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    fun readRawLongLE(): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((readRawByte().toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    fun readRawDoubleLE(): Double {
        val bits = readRawLongLE()
        val d = java.lang.Double.longBitsToDouble(bits)
        return if (d.isFinite()) d else 0.0
    }

    fun read2RD(): Vec2 = Vec2(readRawDoubleLE(), readRawDoubleLE())

    fun readBB(): Int = readBits(2)

    fun readBS(): Int {
        return when (readBB()) {
            0 -> readRawSignedShortLE()
            1 -> readRawByte()
            2 -> 0
            else -> 256
        }
    }

    fun readBSUnsigned(): Int = readBS() and 0xFFFF

    fun readBL(): Int {
        return when (readBB()) {
            0 -> readRawIntLE()
            1 -> readRawByte()
            2 -> 0
            else -> 468
        }
    }

    fun readBLL(): Long {
        val len = readBits(3)
        var res = 0L
        for (i in 0 until len) {
            res = res or ((readRawByte().toLong() and 0xFFL) shl (i * 8))
        }
        return res
    }

    fun readBOT(version: DwgVersion): Int {
        return if (version.isR2010Plus) {
            when (readBB()) {
                0 -> readRawByte()
                1 -> readRawByte() + 0x1F0
                else -> readRawShortLE()
            }
        } else {
            readBSUnsigned()
        }
    }

    fun readBD(): Double {
        return when (readBB()) {
            0 -> readRawDoubleLE()
            1 -> 1.0
            2 -> 0.0
            else -> 0.0
        }
    }

    fun readDD(defaultVal: Double): Double {
        val code = readBB()
        if (code == 0) return defaultVal
        if (code == 3) return readRawDoubleLE()
        val rawBits = java.lang.Double.doubleToRawLongBits(defaultVal)
        val buf = ByteArray(8)
        for (i in 0 until 8) {
            buf[i] = ((rawBits ushr (i * 8)) and 0xFFL).toByte()
        }
        if (code == 1) {
            buf[0] = readRawByte().toByte()
            buf[1] = readRawByte().toByte()
            buf[2] = readRawByte().toByte()
            buf[3] = readRawByte().toByte()
        } else { // code == 2
            buf[4] = readRawByte().toByte()
            buf[5] = readRawByte().toByte()
            buf[0] = readRawByte().toByte()
            buf[1] = readRawByte().toByte()
            buf[2] = readRawByte().toByte()
            buf[3] = readRawByte().toByte()
        }
        var outBits = 0L
        for (i in 0 until 8) {
            outBits = outBits or ((buf[i].toLong() and 0xFFL) shl (i * 8))
        }
        val d = java.lang.Double.longBitsToDouble(outBits)
        return if (d.isFinite()) d else defaultVal
    }

    fun read3BD(): Vec2 {
        val x = readBD()
        val y = readBD()
        readBD() // Z ignored for 2D planar drafting
        return Vec2(x, y)
    }

    fun readBT(version: DwgVersion): Double {
        if (!version.isR2000Plus) return readBD()
        return if (readBit() == 1) 0.0 else readBD()
    }

    fun readBE(version: DwgVersion) {
        if (!version.isR2000Plus) {
            readBD(); readBD(); readBD()
        } else if (readBit() == 0) {
            readBD(); readBD(); readBD()
        }
    }

    fun readTV(version: DwgVersion): String {
        val len = readBS().coerceIn(0, 32768)
        if (len == 0) return ""
        return if (version.isR2007Plus) {
            val bytes = readRawBytes((len * 2).coerceAtMost(65536))
            String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
        } else {
            val bytes = readRawBytes(len.coerceAtMost(65536))
            String(bytes, Charsets.UTF_8).trimEnd('\u0000')
        }
    }

    fun readTU(): String {
        val len = readBS().coerceIn(0, 32768)
        if (len == 0) return ""
        val bytes = readRawBytes((len * 2).coerceAtMost(65536))
        return String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
    }

    fun readHandle(): Pair<Int, Long> {
        val code = readBits(4)
        val counter = readBits(4)
        var handle = 0L
        for (i in 0 until counter) {
            handle = (handle shl 8) or (readRawByte().toLong() and 0xFFL)
        }
        return code to handle
    }

    fun resolveHandle(selfHandle: Long): Pair<Int, Long> {
        val (code, raw) = readHandle()
        val resolved = when (code) {
            0x6 -> selfHandle + 1L
            0x8 -> (selfHandle - 1L).coerceAtLeast(0L)
            0xA -> selfHandle + raw
            0xC -> (selfHandle - raw).coerceAtLeast(0L)
            else -> raw
        }
        return code to resolved
    }

    fun readCMC(version: DwgVersion, strReader: DwgBitReader? = null): Int? {
        val idx = readBS()
        if (version.isR2004Plus) {
            val rgbVal = readBL()
            val colorByte = readRawByte()
            if (!version.isR2007Plus) {
                if ((colorByte and 1) != 0) readTV(version)
                if ((colorByte and 2) != 0) readTV(version)
            } else if (strReader != null) {
                if ((colorByte and 1) != 0) strReader.readTU()
                if ((colorByte and 2) != 0) strReader.readTU()
            }
            if ((rgbVal and 0x01000000) != 0) {
                return (0xFF shl 24) or (rgbVal and 0x00FFFFFF)
            }
        }
        return if (idx in 1..255) AciColors.toArgb(idx) else null
    }

    fun readMC(): Long {
        var result = 0L
        var shift = 0
        for (i in 0 until 8) {
            val b = readRawByte()
            val hasMore = (b and 0x80) != 0
            if (!hasMore) {
                val negative = (b and 0x40) != 0
                val low = (b and 0x3F).toLong() shl shift
                result = result or low
                return if (negative) -result else result
            } else {
                result = result or ((b and 0x7F).toLong() shl shift)
                shift += 7
            }
        }
        return result
    }

    fun readUMC(): Long {
        var result = 0L
        var shift = 0
        for (i in 0 until 8) {
            val b = readRawByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    fun readMS(): Int {
        var result = 0
        var shift = 0
        for (i in 0 until 4) {
            val word = readRawShortLE()
            result = result or ((word and 0x7FFF) shl shift)
            if ((word and 0x8000) == 0) break
            shift += 15
        }
        return result
    }
}

object DwgBinaryDecoder {

    private data class DecodedStreams(
        val version: DwgVersion,
        val classesBytes: ByteArray,
        val handlesBytes: ByteArray,
        val objectsBytes: ByteArray,
        val objectsFileOffsetBase: Long = 0L
    )

    private data class CommonEntityInfo(
        val colorArgb: Int?,
        val entMode: Int,
        val numReactors: Int,
        val xdicMissing: Int,
        val nolinks: Int,
        val ltypeFlags: Int,
        val inlineLayerHandle: Long?
    )

    private data class RawObjectInfo(
        val handle: Long,
        val typeCode: Int,
        val layerHandle: Long?,
        val entity: CadEntity?,
        val warningType: String? = null
    )

    private data class ObjectHeaderFrame(
        val objByteSize: Int,
        val typeCode: Int,
        val handleStreamBitPos: Long,
        val stringReader: DwgBitReader?
    )

    fun decode(bytes: ByteArray, drawingName: String, sourceUri: String? = null): DwgOperationResult {
        if (bytes.size < 25) {
            return DwgOperationResult.UnsupportedBinaryDwg(
                detectedVersion = DwgEngine.inspectVersionHeader(bytes),
                diagnosticMessage = "DWG file is too small or truncated (${bytes.size} bytes)."
            )
        }
        val header6 = bytes.copyOfRange(0, 6).decodeToString()
        val version = DwgVersion.fromHeader(header6)
            ?: return DwgOperationResult.UnsupportedBinaryDwg(
                detectedVersion = DwgEngine.inspectVersionHeader(bytes),
                diagnosticMessage = "Unrecognized binary DWG signature '$header6'."
            )

        if (version == DwgVersion.R2007) {
            return DwgOperationResult.UnsupportedBinaryDwg(
                detectedVersion = version.label,
                diagnosticMessage = "Detected ${version.label} (AC1021), which uses Reed-Solomon interleaved compression. " +
                    "Resave as AutoCAD 2000/2004/2010–2025 DWG (AC1015/AC1018/AC1024/AC1032) or DXF, or bundle native LibreDWG."
            )
        }

        val streams = try {
            if (!version.isR2004Plus) {
                extractR13ToR2000Streams(bytes, version)
            } else {
                extractR2004PlusStreams(bytes, version)
            }
        } catch (e: Throwable) {
            return DwgOperationResult.UnsupportedBinaryDwg(
                detectedVersion = version.label,
                diagnosticMessage = "Could not unpack ${version.label} binary sections (${e.message ?: "corrupt header/section map"})."
            )
        } ?: return DwgOperationResult.UnsupportedBinaryDwg(
            detectedVersion = version.label,
            diagnosticMessage = "Missing required object/handle sections in ${version.label}."
        )

        val classMap = parseClassesSection(streams.classesBytes, version)
        val handleOffsets = parseHandlesSection(streams.handlesBytes)
        if (handleOffsets.isEmpty()) {
            return DwgOperationResult.UnsupportedBinaryDwg(
                detectedVersion = version.label,
                diagnosticMessage = "DWG handle map in ${version.label} yielded 0 object records."
            )
        }

        val objReader = DwgBitReader(streams.objectsBytes)
        val strReader = DwgBitReader(streams.objectsBytes)
        val totalSize = streams.objectsBytes.size

        // PASS 1: Decode LAYER (51) & BLOCK_HEADER (49) records first so entity layer handles resolve regardless of file order
        val layersByHandle = mutableMapOf<Long, CadLayer>()
        val blockHeaders = mutableMapOf<Long, String>()

        for ((handle, rawOffset) in handleOffsets) {
            val localOffset = (rawOffset - streams.objectsFileOffsetBase).toInt()
            if (localOffset < 0 || localOffset >= totalSize - 2) continue
            try {
                val frame = readObjectFrame(objReader, strReader, localOffset, totalSize, version) ?: continue
                when (frame.typeCode) {
                    51 -> decodeLayer(objReader, version, frame.stringReader)?.let { layersByHandle[handle] = it }
                    49 -> decodeBlockHeaderName(objReader, version, frame.stringReader)?.let { blockHeaders[handle] = it }
                }
            } catch (_: Throwable) {
            }
        }

        // PASS 2: Decode geometric entities
        val rawEntities = mutableListOf<RawObjectInfo>()
        val entitiesByHandle = mutableMapOf<Long, CadEntity>()
        val unsupportedCounts = mutableMapOf<String, Int>()
        val poly2DVertexCollector = mutableListOf<Vec2>()
        var activePolyLayerHandle: Long? = null
        var activePolyColor: Int? = null
        var activePolyClosed = false

        for ((handle, rawOffset) in handleOffsets) {
            val localOffset = (rawOffset - streams.objectsFileOffsetBase).toInt()
            if (localOffset < 0 || localOffset >= totalSize - 2) continue

            try {
                val frame = readObjectFrame(objReader, strReader, localOffset, totalSize, version) ?: continue
                val typeCode = frame.typeCode
                if (typeCode == 51 || typeCode == 49) continue
                val className = if (typeCode >= 500) classMap[typeCode] else null

                when (typeCode) {
                    15 -> { // POLYLINE (2D)
                        poly2DVertexCollector.clear()
                        val info = decodeEntityRecord(
                            objReader, frame, version, handle, typeCode, className, layersByHandle
                        )
                        activePolyLayerHandle = info?.layerHandle
                        activePolyColor = info?.entity?.colorArgb
                        activePolyClosed = (info?.entity as? CadPolyline)?.isClosed ?: false
                    }
                    10 -> { // VERTEX (2D)
                        val pt = decodeVertex2D(objReader, version, frame)
                        if (pt != null) poly2DVertexCollector.add(pt)
                    }
                    6 -> { // SEQEND
                        if (poly2DVertexCollector.size >= 2) {
                            val poly = CadPolyline(
                                layerName = "0",
                                colorArgb = activePolyColor,
                                points = poly2DVertexCollector.toList(),
                                isClosed = activePolyClosed
                            )
                            rawEntities.add(RawObjectInfo(handle, 15, activePolyLayerHandle, poly))
                        }
                        poly2DVertexCollector.clear()
                    }
                    else -> {
                        val decoded = decodeEntityRecord(
                            objReader, frame, version, handle, typeCode, className, layersByHandle
                        )
                        if (decoded != null) {
                            if (decoded.entity != null) {
                                rawEntities.add(decoded)
                                entitiesByHandle[handle] = decoded.entity
                            } else if (decoded.warningType != null) {
                                unsupportedCounts[decoded.warningType] = (unsupportedCounts[decoded.warningType] ?: 0) + 1
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Skip individual exotic/unhandled object without failing remaining geometry
            }
        }

        if (poly2DVertexCollector.size >= 2) {
            rawEntities.add(
                RawObjectInfo(
                    handle = 0L,
                    typeCode = 15,
                    layerHandle = activePolyLayerHandle,
                    entity = CadPolyline(
                        layerName = "0",
                        colorArgb = activePolyColor,
                        points = poly2DVertexCollector.toList(),
                        isClosed = activePolyClosed
                    )
                )
            )
        }

        // Resolve layer handles -> layer names
        val resolvedEntities = rawEntities.mapNotNull { info ->
            val e = info.entity ?: return@mapNotNull null
            val layer = info.layerHandle?.let { layersByHandle[it] }
            if (layer != null) e.copyCommon(layerName = layer.name) else e
        }

        val mergedLayers = CadDocument.defaultLayers().associateBy { it.name }.toMutableMap()
        for (layer in layersByHandle.values) {
            mergedLayers[layer.name] = layer
        }
        for (e in resolvedEntities) {
            if (!mergedLayers.containsKey(e.layerName)) {
                mergedLayers[e.layerName] = CadLayer(e.layerName)
            }
        }

        if (resolvedEntities.isEmpty() && handleOffsets.size > 20 && unsupportedCounts.isEmpty()) {
            return DwgOperationResult.UnsupportedBinaryDwg(
                detectedVersion = version.label,
                diagnosticMessage = "Detected ${version.label} (${bytes.size / 1024} KB), but could not extract 2D geometry from this file's object layout. " +
                    "Export as ASCII DXF or AutoCAD 2000/2004 DWG (AC1015/AC1018) for full editing."
            )
        }

        val warnings = if (unsupportedCounts.isNotEmpty()) {
            listOf(
                "Opened ${version.label}; skipped advanced objects: " +
                    unsupportedCounts.entries.take(6).joinToString(", ") { "${it.key} (${it.value})" }
            )
        } else {
            listOf("Opened ${version.label} (${resolvedEntities.size} entities)")
        }

        val doc = CadDocument(
            name = drawingName,
            units = CadUnits.MILLIMETERS,
            layers = mergedLayers.values.toList(),
            currentLayerName = mergedLayers.keys.firstOrNull() ?: "0",
            blocks = emptyMap<String, CadBlockDefinition>(),
            entities = resolvedEntities,
            sourceFormat = CadSourceFormat.DWG,
            sourceUri = sourceUri,
            importWarnings = warnings
        )
        return DwgOperationResult.Success(doc, version.label)
    }

    private fun readObjectFrame(
        r: DwgBitReader,
        strReader: DwgBitReader,
        localOffset: Int,
        totalSize: Int,
        version: DwgVersion
    ): ObjectHeaderFrame? {
        r.seekByte(localOffset)
        val objByteSize = r.readMS()
        if (objByteSize <= 0 || localOffset + objByteSize > totalSize + 64) return null

        val objStartBit = r.bitPos
        if (version.isR2010Plus) {
            val handleStreamBits = r.readUMC().coerceIn(0L, objByteSize.toLong() * 8L)
            val dataEndBit = objStartBit + (objByteSize.toLong() * 8L) - handleStreamBits
            val typeCode = r.readBOT(version)

            // Locate optional R2010+ String Stream right before dataEndBit
            var activeStrReader: DwgBitReader? = null
            if (dataEndBit > objStartBit + 16 && dataEndBit <= r.totalBits) {
                strReader.seekBit(dataEndBit - 1L)
                val hasStrings = strReader.readBit()
                if (hasStrings == 1 && dataEndBit >= objStartBit + 17) {
                    strReader.seekBit(dataEndBit - 17L)
                    var strBitSize = strReader.readRawShortLE().toLong()
                    var headerBits = 17L
                    if ((strBitSize and 0x8000L) != 0L && dataEndBit >= objStartBit + 33) {
                        strBitSize = strBitSize and 0x7FFFL
                        strReader.seekBit(dataEndBit - 33L)
                        val hi = strReader.readRawShortLE().toLong()
                        strBitSize = strBitSize or (hi shl 15)
                        headerBits = 33L
                    }
                    val strStartBit = dataEndBit - headerBits - strBitSize
                    if (strStartBit >= r.bitPos && strBitSize in 1L..262144L) {
                        strReader.seekBit(strStartBit)
                        activeStrReader = strReader
                    }
                }
            }
            return ObjectHeaderFrame(objByteSize, typeCode, dataEndBit, activeStrReader)
        } else {
            val typeCode = r.readBOT(version)
            val handleStreamBitPos = if (version.isR2000Plus) {
                val bitSize = r.readRawIntLE().toLong() and 0xFFFFFFFFL
                objStartBit + bitSize
            } else {
                objStartBit + objByteSize.toLong() * 8L
            }
            return ObjectHeaderFrame(objByteSize, typeCode, handleStreamBitPos, null)
        }
    }

    // ==================== R13 / R14 / R2000 (AC1012, AC1014, AC1015) ====================

    private fun extractR13ToR2000Streams(bytes: ByteArray, version: DwgVersion): DecodedStreams? {
        if (bytes.size < 0x20) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numLocators = bb.getInt(0x15).coerceIn(0, 16)
        var classesOff = -1; var classesSize = 0
        var handlesOff = -1; var handlesSize = 0

        var pos = 0x19
        for (i in 0 until numLocators) {
            if (pos + 9 > bytes.size) break
            val recNo = bytes[pos].toInt() and 0xFF
            val seeker = bb.getInt(pos + 1)
            val size = bb.getInt(pos + 5)
            pos += 9
            if (seeker < 0 || size <= 0 || seeker + size > bytes.size) continue
            when (recNo) {
                1 -> { classesOff = seeker; classesSize = size }
                2 -> { handlesOff = seeker; handlesSize = size }
            }
        }
        if (handlesOff < 0) return null

        val classesBytes = if (classesOff >= 0) bytes.copyOfRange(classesOff, classesOff + classesSize) else ByteArray(0)
        val handlesBytes = bytes.copyOfRange(handlesOff, handlesOff + handlesSize)
        return DecodedStreams(
            version = version,
            classesBytes = classesBytes,
            handlesBytes = handlesBytes,
            objectsBytes = bytes,
            objectsFileOffsetBase = 0L
        )
    }

    // ==================== R2004 / R2010 / R2013 / R2018 (AC1018, AC1024, AC1027, AC1032) ====================

    internal fun extractR2004PlusStreamsForTest(bytes: ByteArray, version: DwgVersion): Boolean =
        extractR2004PlusStreams(bytes, version) != null

    private fun extractR2004PlusStreams(bytes: ByteArray, version: DwgVersion): DecodedStreams? {
        if (bytes.size < 0x100) return null
        val encHeader = bytes.copyOfRange(0x80, 0xEC)
        var seed = 1
        for (i in encHeader.indices) {
            seed = seed * 0x343fd + 0x269ec3
            encHeader[i] = (encHeader[i].toInt() xor (seed ushr 16)).toByte()
        }
        val sig = encHeader.copyOfRange(0, 11).decodeToString()
        if (sig != "AcFssFcAJMB") return null

        val hbb = ByteBuffer.wrap(encHeader).order(ByteOrder.LITTLE_ENDIAN)
        val sectionPageMapAddr = hbb.getLong(0x58) + 0x100L
        val sectionMapId = hbb.getInt(0x60)

        // Read Section Page Map system page
        val pageMapRaw = readSystemPage(bytes, sectionPageMapAddr.toInt(), expectedTag = 0x41630E3B) ?: return null
        val pageOffsets = mutableMapOf<Int, Pair<Long, Long>>() // pageId -> (fileOffset, pageSize)
        val pmBB = ByteBuffer.wrap(pageMapRaw).order(ByteOrder.LITTLE_ENDIAN)
        var fileCursor = 0x100L
        var pmPos = 0
        while (pmPos + 8 <= pageMapRaw.size) {
            val pageId = pmBB.getInt(pmPos)
            val pageSize = pmBB.getInt(pmPos + 4).toLong() and 0xFFFFFFFFL
            pmPos += 8
            if (pageId < 0) {
                // Gap / negative node includes 16 extra tree-node bytes in ODA spec
                pmPos += 16
            } else if (pageId > 0) {
                pageOffsets[pageId] = fileCursor to pageSize
            }
            if (pageSize > 0) {
                fileCursor += pageSize
            }
        }

        val secMapPage = pageOffsets[sectionMapId] ?: return null
        val secMapRaw = readSystemPage(bytes, secMapPage.first.toInt(), expectedTag = 0x4163003B) ?: return null
        val smBB = ByteBuffer.wrap(secMapRaw).order(ByteOrder.LITTLE_ENDIAN)
        if (secMapRaw.size < 20) return null
        val numSections = smBB.getInt(0).coerceIn(0, 128)
        var smPos = 20

        val namedSectionBytes = mutableMapOf<String, ByteArray>()

        for (s in 0 until numSections) {
            if (smPos + 96 > secMapRaw.size) break
            val dataSize = smBB.getLong(smPos).toInt().coerceAtLeast(0)
            val pageCount = smBB.getInt(smPos + 8).coerceIn(0, 8192)
            val maxDecompPageSize = smBB.getInt(smPos + 12).coerceAtLeast(0x7400)
            val compressed = smBB.getInt(smPos + 20) // 2 = compressed
            val nameBytes = secMapRaw.copyOfRange(smPos + 32, smPos + 96)
            val secName = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
            smPos += 96

            val needSection = secName in setOf("AcDb:Classes", "AcDb:Handles", "AcDb:AcDbObjects")
            val assembled = if (needSection && dataSize in 1..(128 * 1024 * 1024)) ByteArray(dataSize) else null

            for (p in 0 until pageCount) {
                if (smPos + 16 > secMapRaw.size) break
                val pageNum = smBB.getInt(smPos)
                val pDataSize = smBB.getInt(smPos + 4).coerceAtLeast(0)
                val startOffset = smBB.getLong(smPos + 8).toInt().coerceAtLeast(0)
                smPos += 16

                if (assembled == null) continue
                val pageLoc = pageOffsets[pageNum] ?: continue
                val fileOff = pageLoc.first.toInt()
                if (fileOff < 0 || fileOff + 32 > bytes.size) continue

                // Unmask 32-byte data page header
                val hdr = bytes.copyOfRange(fileOff, fileOff + 32)
                val secMask = 0x4164536b xor fileOff
                val hIntBB = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until 8) {
                    val v = hIntBB.getInt(k * 4) xor secMask
                    hIntBB.putInt(k * 4, v)
                }
                val compSize = hIntBB.getInt(12).coerceAtLeast(0)
                val uncompSize = pDataSize.coerceAtMost(maxDecompPageSize)
                val payloadStart = fileOff + 32
                if (payloadStart + compSize > bytes.size) continue

                val pageData = if (compressed == 2 && compSize > 0) {
                    decompressR2004(bytes, payloadStart, compSize, uncompSize)
                } else {
                    bytes.copyOfRange(payloadStart, (payloadStart + uncompSize).coerceAtMost(bytes.size))
                }
                val copyLen = minOf(pageData.size, assembled.size - startOffset)
                if (copyLen > 0) {
                    System.arraycopy(pageData, 0, assembled, startOffset, copyLen)
                }
            }

            if (assembled != null) {
                namedSectionBytes[secName] = assembled
            }
        }

        val handles = namedSectionBytes["AcDb:Handles"] ?: return null
        val objects = namedSectionBytes["AcDb:AcDbObjects"] ?: return null
        return DecodedStreams(
            version = version,
            classesBytes = namedSectionBytes["AcDb:Classes"] ?: ByteArray(0),
            handlesBytes = handles,
            objectsBytes = objects,
            objectsFileOffsetBase = 0L
        )
    }

    private fun readSystemPage(bytes: ByteArray, offset: Int, expectedTag: Int): ByteArray? {
        if (offset < 0 || offset + 20 > bytes.size) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val tag = bb.getInt(offset)
        if (tag != expectedTag && tag != 0x41630E3B && tag != 0x4163003B) return null
        val decompSize = bb.getInt(offset + 4).coerceIn(0, 32 * 1024 * 1024)
        val compSize = bb.getInt(offset + 8).coerceIn(0, bytes.size - offset - 20)
        val compType = bb.getInt(offset + 12)
        val payloadOff = offset + 20
        return if (compType == 2 && compSize > 0) {
            decompressR2004(bytes, payloadOff, compSize, decompSize)
        } else {
            bytes.copyOfRange(payloadOff, (payloadOff + decompSize).coerceAtMost(bytes.size))
        }
    }

    /**
     * Exact Open Design Specification R2004+ LZ77 Decompressor.
     */
    internal fun decompressR2004(src: ByteArray, srcStart: Int, compSize: Int, decompSize: Int): ByteArray {
        val dst = ByteArray(decompSize.coerceAtLeast(0))
        if (compSize <= 0 || decompSize <= 0) return dst
        val srcEnd = (srcStart + compSize).coerceAtMost(src.size)
        var sIdx = srcStart
        var dIdx = 0

        fun nextByte(): Int = if (sIdx < srcEnd) (src[sIdx++].toInt() and 0xFF) else 0

        fun readLongBytes(base: Int): Int {
            var total = base
            var b = nextByte()
            while (b == 0 && sIdx < srcEnd) {
                total += 0xFF
                b = nextByte()
            }
            return total + b
        }

        var opCode = nextByte()
        var hadLiteralRun = false
        if (opCode < 0x10) {
            val litLen = if (opCode == 0) readLongBytes(0x12) else (opCode + 3)
            val count = minOf(litLen, dst.size - dIdx, srcEnd - sIdx)
            System.arraycopy(src, sIdx, dst, dIdx, count)
            sIdx += count
            dIdx += count
            hadLiteralRun = true
        }

        while (sIdx < srcEnd && dIdx < dst.size) {
            opCode = nextByte()
            var matchLen: Int
            var matchOff: Int
            var tailLit: Int

            if (opCode < 0x10) {
                if (!hadLiteralRun) {
                    val litLen = if (opCode == 0) readLongBytes(0x12) else (opCode + 3)
                    val count = minOf(litLen, dst.size - dIdx, srcEnd - sIdx)
                    System.arraycopy(src, sIdx, dst, dIdx, count)
                    sIdx += count
                    dIdx += count
                    hadLiteralRun = true
                    continue
                } else {
                    val b0 = nextByte()
                    matchOff = ((b0 shl 2) or ((opCode and 0x0C) ushr 2)) + 0x801
                    matchLen = 3
                    tailLit = opCode and 0x03
                    hadLiteralRun = false
                }
            } else if (opCode < 0x20) {
                val lenLow = opCode and 0x07
                matchLen = (if (lenLow == 0) readLongBytes(0x07) else lenLow) + 2
                val b0 = nextByte()
                val b1 = nextByte()
                matchOff = (((opCode and 0x08) shl 11) or (b1 shl 6) or (b0 ushr 2)) + 0x4000
                tailLit = b0 and 0x03
                hadLiteralRun = false
                if (opCode == 0x11 && b0 == 0 && b1 == 0) break
            } else if (opCode < 0x40) {
                val lenLow = opCode and 0x1F
                matchLen = (if (lenLow == 0) readLongBytes(0x1F) else lenLow) + 2
                val b0 = nextByte()
                val b1 = nextByte()
                matchOff = ((b1 shl 6) or (b0 ushr 2)) + 1
                tailLit = b0 and 0x03
                hadLiteralRun = false
            } else {
                matchLen = (opCode ushr 4) - 1
                val b0 = nextByte()
                matchOff = ((b0 shl 2) or ((opCode and 0x0C) ushr 2)) + 1
                tailLit = opCode and 0x03
                hadLiteralRun = false
            }

            if (matchOff > 0 && dIdx - matchOff >= 0) {
                val count = minOf(matchLen, dst.size - dIdx)
                for (i in 0 until count) {
                    dst[dIdx] = dst[dIdx - matchOff]
                    dIdx++
                }
            }

            if (tailLit > 0) {
                val count = minOf(tailLit, dst.size - dIdx, srcEnd - sIdx)
                System.arraycopy(src, sIdx, dst, dIdx, count)
                sIdx += count
                dIdx += count
            }
        }
        return dst
    }

    // ==================== Classes & Handles Parsers ====================

    private val CLASSES_SENTINEL = byteArrayOf(
        0x8D.toByte(), 0xA1.toByte(), 0xC4.toByte(), 0xB8.toByte(),
        0xC4.toByte(), 0xA9.toByte(), 0xF8.toByte(), 0xC5.toByte(),
        0xC0.toByte(), 0xDC.toByte(), 0xF4.toByte(), 0x5F.toByte(),
        0xE7.toByte(), 0xCF.toByte(), 0xB6.toByte(), 0x8A.toByte()
    )

    private fun parseClassesSection(bytes: ByteArray, version: DwgVersion): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        if (bytes.size < 24) return result
        val start = if (bytes.copyOfRange(0, 16).contentEquals(CLASSES_SENTINEL)) 16 else 0
        val r = DwgBitReader(bytes, start)
        val sizeBytes = r.readRawIntLE().coerceIn(0, bytes.size)
        val endBit = (start + 4 + sizeBytes).toLong() * 8L
        if (version.isR2010Plus) {
            r.readBL()
            r.readBit(); r.readBit(); r.readBit()
        }
        var safety = 0
        while (r.bitPos < endBit - 16 && safety < 2000) {
            safety++
            try {
                val classNum = r.readBS()
                val versionFlag = r.readBS()
                val appName = r.readTV(version)
                val cppName = r.readTV(version)
                val dxfName = r.readTV(version).uppercase()
                r.readBit() // wasZombie
                r.readBS()  // itemClassId
                if (version.isR2004Plus) {
                    r.readBL(); r.readBL(); r.readBL(); r.readBL()
                }
                if (classNum >= 500 && dxfName.isNotEmpty() && versionFlag >= 0 && (appName.isNotEmpty() || cppName.isNotEmpty())) {
                    result[classNum] = dxfName
                }
            } catch (_: Throwable) {
                break
            }
        }
        return result
    }

    private fun parseHandlesSection(bytes: ByteArray): List<Pair<Long, Long>> {
        val pairs = mutableListOf<Pair<Long, Long>>()
        if (bytes.size < 4) return pairs
        var pos = 0
        while (pos + 2 <= bytes.size) {
            // Section block size is 2-byte BIG-ENDIAN
            val blockSize = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            if (blockSize <= 2 || pos + blockSize > bytes.size) break
            val blockEnd = pos + blockSize - 2 // exclude 2-byte CRC
            val r = DwgBitReader(bytes, pos)
            var lastHandle = 0L
            var lastOffset = 0L
            while ((r.bitPos ushr 3).toInt() < blockEnd) {
                val hDelta = r.readUMC()
                val oDelta = r.readMC()
                lastHandle += hDelta
                lastOffset += oDelta
                if (lastHandle > 0 && lastOffset >= 0) {
                    pairs.add(lastHandle to lastOffset)
                }
            }
            pos += blockSize
        }
        return pairs
    }

    // ==================== Table & Entity Object Decoders ====================

    private fun skipTableObjectHeader(r: DwgBitReader, version: DwgVersion) {
        if (!version.isR2007Plus) {
            r.readHandle() // selfHandle (R13–R2004)
        }
        var eedSize = r.readBS()
        while (eedSize > 0) {
            r.readHandle()
            r.readRawBytes(eedSize.coerceAtMost(4096))
            eedSize = r.readBS()
        }
        if (!version.isR2000Plus) {
            r.readBS()
        } else {
            r.readBL() // numReactors
            if (version.isR2004Plus) r.readBit() // xdicMissing
            if (version.isR2013Plus) r.readBit() // hasDsBinaryData
        }
    }

    private fun decodeLayer(
        r: DwgBitReader,
        version: DwgVersion,
        strReader: DwgBitReader?
    ): CadLayer? {
        skipTableObjectHeader(r, version)
        val name = if (version.isR2007Plus) {
            strReader?.readTU()?.trim().orEmpty()
        } else {
            r.readTV(version).trim()
        }
        if (name.isEmpty() || name.startsWith("|")) return null
        r.readBit() // 64 flag
        r.readBS()  // xref index
        r.readBit() // xdep
        val flags = r.readBS()
        val colorArgb = r.readCMC(version, strReader) ?: 0xFFFFFFFF.toInt()
        return CadLayer(
            name = name,
            colorArgb = colorArgb,
            aciColor = AciColors.nearestAci(colorArgb),
            isFrozen = (flags and 1) != 0,
            isOn = (flags and 2) == 0,
            isLocked = (flags and 8) != 0
        )
    }

    private fun decodeBlockHeaderName(
        r: DwgBitReader,
        version: DwgVersion,
        strReader: DwgBitReader?
    ): String? {
        skipTableObjectHeader(r, version)
        val name = if (version.isR2007Plus) {
            strReader?.readTU()?.trim().orEmpty()
        } else {
            r.readTV(version).trim()
        }
        return name.ifEmpty { null }
    }

    private fun readCommonEntityData(
        r: DwgBitReader,
        version: DwgVersion,
        strReader: DwgBitReader?
    ): CommonEntityInfo {
        if (!version.isR2007Plus) {
            r.readHandle() // entity handle (R13–R2004)
        }

        // Skip EED (Extended Entity Data)
        var eedSize = r.readBS()
        while (eedSize > 0) {
            r.readHandle()
            r.readRawBytes(eedSize.coerceAtMost(4096))
            eedSize = r.readBS()
        }

        val graphicPresent = r.readBit()
        if (graphicPresent == 1) {
            val gSize = if (version.isR2010Plus) r.readBLL().toInt() else r.readBL()
            if (gSize in 1..(1024 * 1024)) {
                r.skipBits(gSize.toLong() * 8L)
            }
        }

        val entMode = r.readBB()
        val numReactors = r.readBL().coerceIn(0, 64)
        val xdicMissing = if (version.isR2004Plus) r.readBit() else 0
        val nolinks = if (version.isR2000Plus) r.readBit() else 1

        val colorArgb = r.readCMC(version, strReader)
        r.readBD() // linetype scale
        val ltypeFlags = if (version.isR2000Plus) r.readBB() else 0
        if (version.isR2000Plus) r.readBB() // plotFlags
        if (version.isR2007Plus) {
            r.readBB() // materialFlags
            r.readRawByte() // shadowFlags
        }
        if (version.isR2010Plus) {
            if (r.readBit() == 1) r.readBB() // fullVisualStyle etc.
        }
        r.readBS() // invisibility
        if (version.isR2000Plus) r.readRawByte() // lineweight
        if (version.isR2013Plus) r.readBit() // hasDsBinaryData

        return CommonEntityInfo(
            colorArgb = colorArgb,
            entMode = entMode,
            numReactors = numReactors,
            xdicMissing = xdicMissing,
            nolinks = nolinks,
            ltypeFlags = ltypeFlags,
            inlineLayerHandle = null
        )
    }

    private fun resolveEntityLayerHandle(
        r: DwgBitReader,
        frame: ObjectHeaderFrame,
        version: DwgVersion,
        selfHandle: Long,
        common: CommonEntityInfo,
        layersByHandle: Map<Long, CadLayer>
    ): Long? {
        if (layersByHandle.isEmpty()) return null
        if (frame.handleStreamBitPos <= 0L || frame.handleStreamBitPos >= r.totalBits) return null
        r.seekBit(frame.handleStreamBitPos)
        if (common.entMode == 0) {
            r.resolveHandle(selfHandle) // owner handle
        }
        for (i in 0 until common.numReactors) {
            r.resolveHandle(selfHandle) // reactor handle
        }
        if (common.xdicMissing == 0) {
            r.resolveHandle(selfHandle) // xdic handle
        }
        // Scan up to 4 handles here (covers R2000/R2004/R2010+ regardless of subentity/prev/next order)
        for (probe in 0 until 4) {
            if (r.bitPos >= r.totalBits - 8) break
            val (_, candidate) = r.resolveHandle(selfHandle)
            if (layersByHandle.containsKey(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun decodeVertex2D(
        r: DwgBitReader,
        version: DwgVersion,
        frame: ObjectHeaderFrame
    ): Vec2? {
        readCommonEntityData(r, version, frame.stringReader)
        r.readRawByte() // flags
        return r.read3BD()
    }

    private fun decodeEntityRecord(
        r: DwgBitReader,
        frame: ObjectHeaderFrame,
        version: DwgVersion,
        handle: Long,
        typeCode: Int,
        className: String?,
        layersByHandle: Map<Long, CadLayer>
    ): RawObjectInfo? {
        val common = readCommonEntityData(r, version, frame.stringReader)
        val colorArgb = common.colorArgb

        val resolvedType = when {
            typeCode == 1 -> "TEXT"
            typeCode == 7 -> "INSERT"
            typeCode == 15 -> "POLYLINE"
            typeCode == 17 -> "ARC"
            typeCode == 18 -> "CIRCLE"
            typeCode == 19 -> "LINE"
            typeCode == 27 -> "POINT"
            typeCode == 35 -> "ELLIPSE"
            typeCode == 36 -> "SPLINE"
            typeCode == 40 -> "RAY"
            typeCode == 41 -> "XLINE"
            typeCode == 44 -> "MTEXT"
            typeCode == 77 || className == "LWPOLYLINE" -> "LWPOLYLINE"
            else -> className
        }

        val entity: CadEntity? = when (resolvedType) {
            "LINE" -> {
                if (!version.isR2000Plus) {
                    val s = r.read3BD()
                    val e = r.read3BD()
                    CadLine(colorArgb = colorArgb, start = s, end = e)
                } else {
                    val zIsZero = r.readBit() == 1
                    val sx = r.readRawDoubleLE()
                    val ex = r.readDD(sx)
                    val sy = r.readRawDoubleLE()
                    val ey = r.readDD(sy)
                    if (!zIsZero) {
                        val sz = r.readRawDoubleLE()
                        r.readDD(sz)
                    }
                    CadLine(colorArgb = colorArgb, start = Vec2(sx, sy), end = Vec2(ex, ey))
                }
            }
            "CIRCLE" -> {
                val center = r.read3BD()
                val radius = r.readBD().coerceAtLeast(1e-4)
                CadCircle(colorArgb = colorArgb, center = center, radius = radius)
            }
            "ARC" -> {
                val center = r.read3BD()
                val radius = r.readBD().coerceAtLeast(1e-4)
                r.readBT(version)
                r.readBE(version)
                val startRad = r.readBD()
                val endRad = r.readBD()
                CadArc(
                    colorArgb = colorArgb,
                    center = center,
                    radius = radius,
                    startAngleDeg = Math.toDegrees(startRad),
                    endAngleDeg = Math.toDegrees(endRad)
                )
            }
            "POINT" -> {
                val pt = r.read3BD()
                CadPoint(colorArgb = colorArgb, position = pt)
            }
            "RAY" -> {
                val pt = r.read3BD()
                val dir = r.read3BD()
                CadRay(colorArgb = colorArgb, origin = pt, direction = dir)
            }
            "XLINE" -> {
                val pt = r.read3BD()
                val dir = r.read3BD()
                CadXLine(colorArgb = colorArgb, origin = pt, direction = dir)
            }
            "ELLIPSE" -> {
                val center = r.read3BD()
                val major = r.read3BD()
                r.read3BD() // extrusion
                val ratio = r.readBD().coerceIn(0.05, 1.0)
                val sParam = r.readBD()
                val eParam = r.readBD()
                CadEllipse(
                    colorArgb = colorArgb,
                    center = center,
                    majorAxisEndOffset = major,
                    minorToMajorRatio = ratio,
                    startParamRad = sParam,
                    endParamRad = eParam
                )
            }
            "LWPOLYLINE" -> {
                val flag = r.readBS()
                if ((flag and 4) != 0) r.readBD() // const width
                if ((flag and 8) != 0) r.readBD() // elevation
                if ((flag and 2) != 0) r.readBD() // thickness
                if ((flag and 1) != 0) r.read3BD() // normal
                val numPts = r.readBL().coerceIn(0, 100000)
                if ((flag and 16) != 0) r.readBL() // numBulges
                if (version.isR2010Plus && (flag and 1024) != 0) r.readBL() // vertexIdCount
                if ((flag and 32) != 0) r.readBL() // numWidths
                val pts = ArrayList<Vec2>(numPts)
                if (numPts > 0) {
                    if (!version.isR2000Plus) {
                        for (i in 0 until numPts) pts.add(r.read2RD())
                    } else {
                        var lastPt = r.read2RD()
                        pts.add(lastPt)
                        for (i in 1 until numPts) {
                            lastPt = Vec2(r.readDD(lastPt.x), r.readDD(lastPt.y))
                            pts.add(lastPt)
                        }
                    }
                }
                if (pts.size >= 2) {
                    CadPolyline(colorArgb = colorArgb, points = pts, isClosed = (flag and 512) != 0)
                } else null
            }
            "POLYLINE" -> {
                val flag = r.readBS()
                CadPolyline(colorArgb = colorArgb, points = emptyList(), isClosed = (flag and 1) != 0)
            }
            "TEXT" -> {
                val dataFlags = if (version.isR2000Plus) r.readRawByte() else 0
                val pt = if ((dataFlags and 1) == 0) r.read2RD() else Vec2.ZERO
                if (!version.isR2000Plus) r.readBD() // elevation
                if ((dataFlags and 2) == 0) r.read2RD() // align pt
                r.readBE(version)
                r.readBT(version)
                if ((dataFlags and 4) == 0) r.readRDOrBD(version) // oblique
                val rotRad = if ((dataFlags and 8) == 0) r.readRDOrBD(version) else 0.0
                val height = if ((dataFlags and 16) == 0) r.readRDOrBD(version).coerceAtLeast(0.5) else 4.0
                if ((dataFlags and 32) == 0) r.readRDOrBD(version) // width factor
                val text = if (version.isR2007Plus) {
                    frame.stringReader?.readTU()?.trim().orEmpty()
                } else {
                    r.readTV(version).trim()
                }
                if (text.isNotEmpty()) {
                    CadText(colorArgb = colorArgb, position = pt, text = text, height = height, rotationDeg = Math.toDegrees(rotRad))
                } else null
            }
            "MTEXT" -> {
                val pt = r.read3BD()
                r.read3BD() // extrusion
                val xAxis = r.read3BD()
                r.readBD() // rectWidth
                val height = r.readBD().coerceAtLeast(0.5)
                r.readBS() // attachment
                r.readBS() // flowDir
                r.readBD(); r.readBD()
                val text = (if (version.isR2007Plus) {
                    frame.stringReader?.readTU().orEmpty()
                } else {
                    r.readTV(version)
                }).replace("\\P", "\n").trim()
                if (text.isNotEmpty()) {
                    CadText(colorArgb = colorArgb, position = pt, text = text, height = height, rotationDeg = xAxis.angleDeg())
                } else null
            }
            "SPLINE" -> {
                val scenario = r.readBL()
                val degree = r.readBL().coerceIn(1, 5)
                if (scenario == 2) {
                    r.readBD(); r.readBD(); r.readBD()
                    val numFit = r.readBL().coerceIn(0, 10000)
                    r.read3BD(); r.read3BD()
                    val pts = (0 until numFit).map { r.read3BD() }
                    if (pts.size >= 2) CadSpline(colorArgb = colorArgb, controlPoints = pts, degree = degree) else null
                } else {
                    r.readBit() // rational
                    val closed = r.readBit() == 1
                    val periodic = r.readBit() == 1
                    r.readBD(); r.readBD()
                    val numKnots = r.readBL().coerceIn(0, 20000)
                    val numCtrl = r.readBL().coerceIn(0, 20000)
                    val weighted = r.readBit() == 1
                    for (i in 0 until numKnots) r.readBD()
                    val pts = ArrayList<Vec2>(numCtrl)
                    for (i in 0 until numCtrl) {
                        pts.add(r.read3BD())
                        if (weighted) r.readBD()
                    }
                    if (pts.size >= 2) {
                        CadSpline(colorArgb = colorArgb, controlPoints = pts, degree = degree, isClosed = closed || periodic)
                    } else null
                }
            }
            "INSERT" -> {
                val ins = r.read3BD()
                val sx: Double
                val sy: Double
                if (!version.isR2000Plus) {
                    sx = r.readBD(); sy = r.readBD(); r.readBD()
                } else {
                    when (r.readBB()) {
                        0 -> { sx = r.readRawDoubleLE(); sy = r.readDD(sx); r.readDD(sx) }
                        1 -> { sx = 1.0; sy = r.readDD(1.0); r.readDD(1.0) }
                        2 -> { sx = r.readRawDoubleLE(); sy = sx }
                        else -> { sx = 1.0; sy = 1.0 }
                    }
                }
                val rotRad = r.readBD()
                CadBlockReference(
                    colorArgb = colorArgb,
                    blockName = "*DWG_BLOCK_${handle}",
                    insertionPoint = ins,
                    scaleX = sx,
                    scaleY = sy,
                    rotationDeg = Math.toDegrees(rotRad)
                )
            }
            else -> null
        }

        val resolvedLayerHandle = if (entity != null) {
            resolveEntityLayerHandle(r, frame, version, handle, common, layersByHandle)
        } else null

        return RawObjectInfo(
            handle = handle,
            typeCode = typeCode,
            layerHandle = resolvedLayerHandle,
            entity = entity,
            warningType = if (entity == null && resolvedType != null && resolvedType !in setOf("POLYLINE", "SEQEND", "VERTEX")) resolvedType else null
        )
    }

    private fun DwgBitReader.readRDOrBD(version: DwgVersion): Double {
        return if (!version.isR2000Plus) readBD() else readRawDoubleLE()
    }
}

package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.cad.engine.ActiveTool
import com.example.cad.engine.CadWorkspaceViewModel
import com.example.cad.engine.CommandProcessor
import com.example.cad.engine.DrawTool
import com.example.cad.engine.ParsedCliInput
import com.example.cad.engine.UndoRedoManager
import com.example.cad.geometry.CadSpatialIndex
import com.example.cad.geometry.GeometryEngine
import com.example.cad.geometry.GeometryOperations
import com.example.cad.geometry.IntArrayList
import com.example.cad.geometry.SnapEngine
import com.example.cad.io.CadExportEngine
import com.example.cad.io.DwgBinaryDecoder
import com.example.cad.io.DwgEngine
import com.example.cad.io.DwgOperationResult
import com.example.cad.io.DwgSdkBridge
import com.example.cad.io.DxfParser
import com.example.cad.io.DxfWriter
import com.example.cad.io.SampleDrawings
import com.example.cad.model.Bounds2D
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.CadSourceFormat
import com.example.cad.model.CadUnits
import com.example.cad.model.DimensionType
import com.example.cad.model.OsnapType
import com.example.cad.model.SnapSettings
import com.example.cad.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CadEngineTest {

    @Test
    fun `dxf round trip preserves layers units blocks dimensions and coordinates`() {
        val sample = SampleDrawings.architecturalStudioFloorPlan()
        val out = ByteArrayOutputStream()
        DxfWriter.write(sample, out)

        val reparsed = DxfParser.parse(ByteArrayInputStream(out.toByteArray()), "RoundTrip")
        assertEquals(CadUnits.CENTIMETERS, reparsed.units)
        assertTrue(reparsed.layers.any { it.name == "WALLS" })
        assertTrue(reparsed.layers.any { it.name == "CENTERLINES" && it.lineType == CadLineType.CENTER })
        assertTrue(reparsed.blocks.containsKey("DOOR_900"))
        assertEquals(sample.entities.size, reparsed.entities.size)
        assertTrue(reparsed.importWarnings.isEmpty())
    }

    @Test
    fun `large dxf file with 2500 entities parses and writes accurately`() {
        val many = (0 until 2500).map { i ->
            CadLine(
                layerName = if (i % 2 == 0) "0" else "HIDDEN",
                start = Vec2(i * 1.0, 0.0),
                end = Vec2(i * 1.0, 50.0)
            )
        }
        val doc = CadDocument(name = "LargeStressTest", entities = many)
        val out = ByteArrayOutputStream()
        DxfWriter.write(doc, out)
        val parsed = DxfParser.parse(ByteArrayInputStream(out.toByteArray()), "LargeStressTest")
        assertEquals(2500, parsed.entities.size)
    }

    @Test
    fun `dxf parser reports informative warning on unsupported entities`() {
        val dxfWithUnsupported = """
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            8
            0
            10
            0.0
            20
            0.0
            11
            10.0
            21
            0.0
            0
            3DSOLID
            8
            0
            0
            ENDSEC
            0
            EOF
        """.trimIndent()
        val parsed = DxfParser.parse(ByteArrayInputStream(dxfWithUnsupported.toByteArray()), "WarnTest")
        assertEquals(1, parsed.entities.size)
        assertTrue(parsed.importWarnings.firstOrNull()?.contains("3DSOLID") == true)
    }

    @Test
    fun `dwg engine detects binary version and delegates to native sdk bridge when present`() {
        val fakeBinaryHeader = "AC1032\u0000\u0000\u0000\u0000".toByteArray()
        val unsupported = DwgEngine.readDwg(ByteArrayInputStream(fakeBinaryHeader), "TestDwg")
        assertTrue(unsupported is DwgOperationResult.UnsupportedBinaryDwg)
        assertTrue((unsupported as DwgOperationResult.UnsupportedBinaryDwg).detectedVersion.contains("2018"))

        // Verify SDK integration bridge path
        val sampleOut = ByteArrayOutputStream()
        DxfWriter.write(SampleDrawings.mechanicalGearBracket(), sampleOut)
        val mockSdkBridge = object : DwgSdkBridge {
            override val sdkName = "TestSDK"
            override fun isNativeLibraryLoaded() = true
            override fun convertDwgToDxf(dwgBytes: ByteArray) = sampleOut.toByteArray()
            override fun convertDxfToDwg(dxfBytes: ByteArray, versionCode: String) = dwgBytesWithHeader(dxfBytes)
            private fun dwgBytesWithHeader(b: ByteArray) = "AC1024".toByteArray() + b
        }
        val success = DwgEngine.readDwg(ByteArrayInputStream(fakeBinaryHeader), "GearDwg", bridge = mockSdkBridge)
        assertTrue(success is DwgOperationResult.Success)
        assertTrue((success as DwgOperationResult.Success).document.entities.isNotEmpty())
    }

    @Test
    fun `dwg binary decoder opens R2000 AC1015 binary dwg with layers and geometric entities`() {
        val dwgBytes = buildSyntheticR2000DwgBytes()
        val result = DwgEngine.readDwg(ByteArrayInputStream(dwgBytes), "BuildingPlan")
        assertTrue("Expected Success but got $result", result is DwgOperationResult.Success)
        val doc = (result as DwgOperationResult.Success).document
        assertEquals(CadSourceFormat.DWG, doc.sourceFormat)
        assertTrue(doc.layers.any { it.name == "WALLS" })

        val line = doc.entities.filterIsInstance<CadLine>().firstOrNull()
        assertNotNull(line)
        assertEquals("WALLS", line!!.layerName)
        assertEquals(10.0, line.start.x, 1e-4)
        assertEquals(20.0, line.start.y, 1e-4)
        assertEquals(110.0, line.end.x, 1e-4)
        assertEquals(70.0, line.end.y, 1e-4)

        val circle = doc.entities.filterIsInstance<CadCircle>().firstOrNull()
        assertNotNull(circle)
        assertEquals(50.0, circle!!.center.x, 1e-4)
        assertEquals(50.0, circle.center.y, 1e-4)
        assertEquals(15.0, circle.radius, 1e-4)

        val arc = doc.entities.filterIsInstance<CadArc>().firstOrNull()
        assertNotNull(arc)
        assertEquals(25.0, arc!!.radius, 1e-4)
        assertEquals(0.0, arc.startAngleDeg, 1e-2)
        assertEquals(90.0, arc.endAngleDeg, 1e-2)

        val poly = doc.entities.filterIsInstance<CadPolyline>().firstOrNull()
        assertNotNull(poly)
        assertTrue(poly!!.isClosed)
        assertEquals(3, poly.points.size)
        assertEquals(40.0, poly.points[2].y, 1e-4)
    }

    @Test
    fun `dwg binary decoder unpacks R2004 AC1018 lz77 compressed pages and decodes entities`() {
        // 1. Verify R2004 LZ77 decompressor with literal run + backreference + termination opcode (0x11, 0x00, 0x00)
        val payload = byteArrayOf(
            0x03, // literal len = 3 + 3 = 6
            'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(),
            0x60, 0x01, // opcode >= 0x40: matchLen = (6-1)=5, matchOff = ((1<<2)|0)+1 = 5, tailLit=0
            0x11, 0x00, 0x00 // end stream
        )
        val decompressed = DwgBinaryDecoder.decompressR2004(payload, 0, payload.size, 11)
        assertEquals("ABCDEFBCDEF", decompressed.decodeToString())

        // 2. Build full synthetic AC1018 container wrapping our R2004 object/handle streams
        val dwgBytes = buildSyntheticR2004DwgBytes()
        val result = DwgEngine.readDwg(ByteArrayInputStream(dwgBytes), "SiteLayout2004")
        assertTrue("Expected R2004 Success but got $result", result is DwgOperationResult.Success)
        val doc = (result as DwgOperationResult.Success).document
        assertEquals(CadSourceFormat.DWG, doc.sourceFormat)
        val line = doc.entities.filterIsInstance<CadLine>().firstOrNull()
        assertNotNull(line)
        assertEquals(5.0, line!!.start.x, 1e-4)
        assertEquals(85.0, line.end.x, 1e-4)
        val circle = doc.entities.filterIsInstance<CadCircle>().firstOrNull()
        assertNotNull(circle)
        assertEquals(12.5, circle!!.radius, 1e-4)
    }

    @Test
    fun `spatial index accelerates 20000 entity hit testing window query and osnap`() {
        val entities = ArrayList<CadEntity>(20000)
        for (i in 0 until 20000) {
            val col = i % 200
            val row = i / 200
            val x0 = col * 10.0
            val y0 = row * 10.0
            if (i % 5 == 0) {
                entities.add(CadCircle(layerName = "0", center = Vec2(x0 + 5.0, y0 + 5.0), radius = 2.0))
            } else {
                entities.add(CadLine(layerName = "0", start = Vec2(x0, y0), end = Vec2(x0 + 8.0, y0 + 6.0)))
            }
        }

        val index = CadSpatialIndex.build(entities, emptyMap())
        assertEquals(20000, index.entityCount)
        assertEquals(3.0, index.totalBounds.minX, 1e-6)
        assertTrue(index.totalBounds.maxX >= 1995.0)

        // Frustum query near center (1200, 500) -> only tiny localized subset returned
        val hits = IntArrayList(64)
        val stamp = index.newStampBuffer()
        index.queryBounds(Bounds2D(1198.0, 498.0, 1212.0, 512.0), hits, stamp, 1)
        assertTrue("Expected localized query < 40 entities, got ${hits.size}", hits.size in 1..40)

        // Hit-test near circle at col=120, row=50 -> center=(1205, 505), radius=2 -> boundary at (1207, 505)
        val hit = GeometryEngine.hitTest(
            worldPoint = Vec2(1206.9, 505.0),
            entities = entities,
            toleranceWorld = 0.5,
            visibleLayerNames = setOf("0"),
            spatialIndex = index
        )
        assertTrue(hit is CadCircle)
        assertEquals(1205.0, (hit as CadCircle).center.x, 1e-4)

        // OSNAP center lock with spatial index
        val snap = SnapEngine.resolveSnap(
            rawWorldPoint = Vec2(1205.3, 505.2),
            anchorPoint = null,
            entities = entities,
            blocks = emptyMap(),
            visibleLayerNames = setOf("0"),
            snapSettings = SnapSettings(osnapEnabled = true),
            snapToleranceWorld = 1.5,
            spatialIndex = index
        )
        assertEquals(OsnapType.CENTER, snap.osnapType)
        assertEquals(1205.0, snap.point.x, 1e-4)
        assertEquals(505.0, snap.point.y, 1e-4)

        // Window selection box
        val selected = GeometryEngine.selectByRect(
            start = Vec2(98.0, 98.0),
            end = Vec2(120.0, 120.0),
            entities = entities,
            visibleLayerNames = setOf("0"),
            spatialIndex = index
        )
        assertTrue(selected.isNotEmpty() && selected.size < 20)
    }

    @Test
    fun `geometry operations trim extend fillet chamfer offset stretch and join work accurately`() {
        // Trim
        val horizontal = CadLine(start = Vec2(0.0, 0.0), end = Vec2(20.0, 0.0))
        val cutter = CadLine(start = Vec2(10.0, -5.0), end = Vec2(10.0, 5.0))
        val trimmed = GeometryOperations.trimEntity(horizontal, Vec2(15.0, 0.0), listOf(horizontal, cutter))
        assertNotNull(trimmed)
        assertEquals(1, trimmed!!.size)
        val kept = trimmed[0] as CadLine
        assertEquals(0.0, kept.start.x, 1e-3)
        assertEquals(10.0, kept.end.x, 1e-3)

        // Extend
        val shortLine = CadLine(start = Vec2(0.0, 0.0), end = Vec2(5.0, 0.0))
        val extended = GeometryOperations.extendEntity(shortLine, Vec2(4.9, 0.0), listOf(shortLine, cutter))
        assertNotNull(extended)
        assertEquals(10.0, extended!!.end.x, 1e-3)

        // Fillet
        val l1 = CadLine(start = Vec2(0.0, 0.0), end = Vec2(20.0, 0.0))
        val l2 = CadLine(start = Vec2(20.0, 0.0), end = Vec2(20.0, 20.0))
        val filletRes = GeometryOperations.filletLines(l1, l2, 4.0)
        assertNotNull(filletRes)
        assertNotNull(filletRes!!.third)
        assertEquals(4.0, filletRes.third!!.radius, 1e-3)

        // Offset
        val off = GeometryOperations.offset(horizontal, 5.0, Vec2(10.0, 10.0)) as CadLine
        assertEquals(5.0, off.start.y, 1e-3)

        // Join
        val seg1 = CadLine(start = Vec2(0.0, 0.0), end = Vec2(10.0, 0.0))
        val seg2 = CadLine(start = Vec2(10.0, 0.0), end = Vec2(10.0, 10.0))
        val joined = GeometryOperations.joinEntities(listOf(seg1, seg2))
        assertNotNull(joined)
        assertEquals(3, joined!!.points.size)

        // Stretch
        val stretched = GeometryOperations.stretch(
            horizontal,
            Bounds2D(15.0, -5.0, 25.0, 5.0),
            Vec2(5.0, 0.0)
        ) as CadLine
        assertEquals(0.0, stretched.start.x, 1e-3)
        assertEquals(25.0, stretched.end.x, 1e-3)
    }

    @Test
    fun `osnap resolves endpoint midpoint center and polar commands`() {
        val line = CadLine(start = Vec2(0.0, 0.0), end = Vec2(100.0, 0.0))
        val circle = CadCircle(center = Vec2(50.0, 50.0), radius = 10.0)
        val settings = SnapSettings(osnapEnabled = true)

        val endSnap = SnapEngine.resolveSnap(Vec2(1.5, 1.0), null, listOf(line), emptyMap(), setOf("0"), settings, 5.0)
        assertEquals(OsnapType.ENDPOINT, endSnap.osnapType)
        assertEquals(0.0, endSnap.point.x, 1e-3)

        val midSnap = SnapEngine.resolveSnap(Vec2(50.5, 1.0), null, listOf(line), emptyMap(), setOf("0"), settings, 5.0)
        assertEquals(OsnapType.MIDPOINT, midSnap.osnapType)
        assertEquals(50.0, midSnap.point.x, 1e-3)

        val cenSnap = SnapEngine.resolveSnap(Vec2(51.0, 50.5), null, listOf(circle), emptyMap(), setOf("0"), settings, 5.0)
        assertEquals(OsnapType.CENTER, cenSnap.osnapType)

        val cliDraw = CommandProcessor.parse("L", null) as ParsedCliInput.SwitchTool
        assertEquals(ActiveTool.Draw(DrawTool.LINE), cliDraw.tool)

        val polarPt = CommandProcessor.parse("@100<0", Vec2(10.0, 20.0)) as ParsedCliInput.PointCoordinate
        assertEquals(110.0, polarPt.point.x, 1e-3)
        assertEquals(20.0, polarPt.point.y, 1e-3)
    }

    @Test
    fun `exports produce valid pdf svg png and jpg outputs`() {
        val doc = SampleDrawings.mechanicalGearBracket()

        val pdfOut = ByteArrayOutputStream()
        CadExportEngine.exportToPdf(doc, pdfOut)
        assertTrue(pdfOut.toByteArray().decodeToString().startsWith("%PDF"))

        val svgOut = ByteArrayOutputStream()
        CadExportEngine.exportToSvg(doc, svgOut)
        val svgText = svgOut.toByteArray().decodeToString()
        assertTrue(svgText.contains("<svg") && svgText.contains("layer_PROFILE"))

        val pngOut = ByteArrayOutputStream()
        CadExportEngine.exportToRaster(doc, pngOut, Bitmap.CompressFormat.PNG, 400, 300)
        assertTrue(pngOut.size() > 100)

        val jpgOut = ByteArrayOutputStream()
        CadExportEngine.exportToRaster(doc, jpgOut, Bitmap.CompressFormat.JPEG, 400, 300)
        assertTrue(jpgOut.size() > 100)
    }

    @Test
    fun `undo redo manager supports multi step revert reapply branch clearing and depth cap`() {
        val manager = UndoRedoManager(defaultMaxDepth = 3)
        val doc0 = CadDocument(name = "Doc0")
        val doc1 = doc0.copy(entities = listOf(CadLine(start = Vec2(0.0, 0.0), end = Vec2(10.0, 0.0))))
        val doc2 = doc0.copy(entities = doc1.entities + CadCircle(center = Vec2(5.0, 5.0), radius = 3.0))
        val doc3 = doc0.copy(entities = doc2.entities + CadLine(start = Vec2(0.0, 10.0), end = Vec2(10.0, 10.0)))
        val doc4 = doc0.copy(entities = doc3.entities + CadLine(start = Vec2(10.0, 0.0), end = Vec2(10.0, 10.0)))

        manager.record(doc0, "Draw Line 1")
        manager.record(doc1, "Draw Circle")
        manager.record(doc2, "Draw Line 2")
        manager.record(doc3, "Draw Line 3") // exceeds depth=3, drops oldest (doc0)

        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
        assertEquals(3, manager.undoCount)
        assertEquals(listOf("Draw Line 3", "Draw Line 2", "Draw Circle"), manager.undoHistoryLabels())
        assertEquals("Draw Line 3", manager.nextUndoLabel)

        // Multi-step undo (2 steps: doc4 -> doc2)
        val reverted = manager.undoSteps(doc4, 2)
        assertNotNull(reverted)
        assertEquals(2, reverted!!.document.entities.size)
        assertEquals("Draw Line 2", reverted.actionLabel)
        assertEquals(1, manager.undoCount)
        assertEquals(2, manager.redoCount)
        assertEquals(listOf("Draw Line 2", "Draw Line 3"), manager.redoHistoryLabels())

        // Redo 1 step (doc2 -> doc3)
        val reapplied = manager.redo(reverted.document)
        assertNotNull(reapplied)
        assertEquals(3, reapplied!!.document.entities.size)
        assertEquals(2, manager.undoCount)
        assertEquals(1, manager.redoCount)

        // Recording a new edit while redo stack has items clears the redo branch
        val branchedDoc = reapplied.document.copy(entities = reapplied.document.entities + CadPoint(position = Vec2(1.0, 1.0)))
        manager.record(reapplied.document, "Draw Point")
        assertFalse(manager.canRedo)
        assertEquals(0, manager.redoCount)
        assertNull(manager.redo(reapplied.document))
        assertEquals(4, branchedDoc.entities.size)
    }

    @Test
    fun `command processor parses undo and redo shortcuts with optional step counts`() {
        assertEquals(ParsedCliInput.UndoCommand(1), CommandProcessor.parse("u", null))
        assertEquals(ParsedCliInput.UndoCommand(1), CommandProcessor.parse("UNDO", null))
        assertEquals(ParsedCliInput.UndoCommand(3), CommandProcessor.parse("undo 3", null))
        assertEquals(ParsedCliInput.UndoCommand(2), CommandProcessor.parse("U 2", null))
        assertEquals(ParsedCliInput.RedoCommand(1), CommandProcessor.parse("REDO", null))
        assertEquals(ParsedCliInput.RedoCommand(4), CommandProcessor.parse("redo 4", null))
    }

    @Test
    fun `dxf parser loads files with utf8 bom leading blank lines and non finite coordinates`() {
        val messyDxf = "\uFEFF\n\n  \n0\nSECTION\n\n2\nENTITIES\n0\nLINE\n8\n0\n10\nNaN\n20\n5.0\n11\n25.0\n21\n15.0\n0\nENDSEC\n0\nEOF\n"
        val parsed = DxfParser.parse(ByteArrayInputStream(messyDxf.toByteArray()), "MessyDxf")
        assertEquals(1, parsed.entities.size)
        val line = parsed.entities.first() as CadLine
        assertEquals(0.0, line.start.x, 1e-6)
        assertEquals(5.0, line.start.y, 1e-6)
        assertEquals(25.0, line.end.x, 1e-6)
    }

    @Test
    fun `workspace viewmodel integrates undo redo for drafting cli layouts and vertex rollback`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CadWorkspaceViewModel(app)
        vm.loadNewDocumentWithParams("UndoTest", "QA", CadUnits.MILLIMETERS)

        assertFalse(vm.uiState.value.canUndo)
        assertEquals(0, vm.uiState.value.document.entities.size)

        // Draw a LINE via CLI
        vm.submitCommandInput("L")
        vm.submitCommandInput("0,0")
        vm.submitCommandInput("100,0")
        assertEquals(1, vm.uiState.value.document.entities.size)
        assertTrue(vm.uiState.value.canUndo)
        assertEquals(1, vm.uiState.value.undoCount)
        assertEquals("LINE", vm.uiState.value.undoDescription)

        // Undo via CLI "U"
        vm.submitCommandInput("U")
        assertEquals(0, vm.uiState.value.document.entities.size)
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.canRedo)
        assertEquals("LINE", vm.uiState.value.redoDescription)

        // Redo via CLI "REDO"
        vm.submitCommandInput("REDO")
        assertEquals(1, vm.uiState.value.document.entities.size)
        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)

        // Buffered PLINE vertex undo removes last picked vertex before committing
        vm.submitCommandInput("PL")
        vm.submitCommandInput("0,0")
        vm.submitCommandInput("10,0")
        vm.submitCommandInput("10,10")
        assertEquals(3, vm.uiState.value.session.collectedPoints.size)
        vm.undo()
        assertEquals(2, vm.uiState.value.session.collectedPoints.size)
        vm.finishActiveTool()
        assertEquals(2, vm.uiState.value.document.entities.size)

        // Layout operations are also undoable
        val initialLayoutCount = vm.uiState.value.document.layouts.size
        vm.addLayout()
        assertEquals(initialLayoutCount + 1, vm.uiState.value.document.layouts.size)
        vm.undo()
        assertEquals(initialLayoutCount, vm.uiState.value.document.layouts.size)

        // Duplicate loadDrawing with same ID does not discard active edits/undo state
        val activeId = vm.uiState.value.document.id
        vm.loadDrawing(activeId, recoverAutosave = false)
        assertEquals(2, vm.uiState.value.document.entities.size)
        assertTrue(vm.uiState.value.canUndo)
    }

    // ==================== Synthetic Binary DWG Builders for Unit Tests ====================

    private class DwgTestBitWriter {
        private val bytes = ArrayList<Byte>(256)
        private var currentByte = 0
        private var bitsUsed = 0
        var totalBitsWritten: Long = 0L
            private set

        fun writeBit(bit: Int) {
            currentByte = (currentByte shl 1) or (bit and 1)
            bitsUsed++
            totalBitsWritten++
            if (bitsUsed == 8) {
                bytes.add(currentByte.toByte())
                currentByte = 0
                bitsUsed = 0
            }
        }

        fun writeBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                writeBit((value ushr i) and 1)
            }
        }

        fun writeBB(code: Int) = writeBits(code, 2)

        fun writeRawByte(v: Int) {
            for (i in 7 downTo 0) {
                writeBit((v ushr i) and 1)
            }
        }

        fun writeRawShortLE(v: Int) {
            writeRawByte(v and 0xFF)
            writeRawByte((v ushr 8) and 0xFF)
        }

        fun writeRawIntLE(v: Int) {
            for (i in 0 until 4) writeRawByte((v ushr (i * 8)) and 0xFF)
        }

        fun writeRawLongLE(v: Long) {
            for (i in 0 until 8) writeRawByte(((v ushr (i * 8)) and 0xFFL).toInt())
        }

        fun writeRawDoubleLE(d: Double) = writeRawLongLE(java.lang.Double.doubleToRawLongBits(d))

        fun writeBS(v: Int) {
            when {
                v == 0 -> writeBB(2)
                v == 256 -> writeBB(3)
                v in 1..255 -> { writeBB(1); writeRawByte(v) }
                else -> { writeBB(0); writeRawShortLE(v) }
            }
        }

        fun writeBL(v: Int) {
            when {
                v == 0 -> writeBB(2)
                v in 1..255 -> { writeBB(1); writeRawByte(v) }
                else -> { writeBB(0); writeRawIntLE(v) }
            }
        }

        fun writeBD(d: Double) {
            when (d) {
                0.0 -> writeBB(2)
                1.0 -> writeBB(1)
                else -> { writeBB(0); writeRawDoubleLE(d) }
            }
        }

        fun write3BD(x: Double, y: Double, z: Double = 0.0) {
            writeBD(x); writeBD(y); writeBD(z)
        }

        fun writeTV(text: String) {
            val raw = text.toByteArray(Charsets.UTF_8)
            writeBS(raw.size)
            for (b in raw) writeRawByte(b.toInt() and 0xFF)
        }

        fun writeHandle(code: Int, value: Long) {
            if (value == 0L) {
                writeBits(code, 4)
                writeBits(0, 4)
            } else {
                writeBits(code, 4)
                writeBits(1, 4)
                writeRawByte(value.toInt() and 0xFF)
            }
        }

        fun writeMS(v: Int) {
            writeRawShortLE(v and 0x7FFF)
        }

        fun toByteArray(): ByteArray {
            val copy = bytes.toMutableList()
            if (bitsUsed > 0) {
                copy.add((currentByte shl (8 - bitsUsed)).toByte())
            }
            return copy.toByteArray()
        }
    }

    private fun buildDwgObjectFrame(
        typeCode: Int,
        isR2004Plus: Boolean,
        writeData: (DwgTestBitWriter) -> Unit,
        writeHandles: ((DwgTestBitWriter) -> Unit)? = null
    ): ByteArray {
        val body = DwgTestBitWriter()
        body.writeBS(typeCode)
        // Reserve 32 bits for RL bitSize by building into temp writer first
        val dataPayload = DwgTestBitWriter()
        writeData(dataPayload)
        val payloadStartBits = 16 + 32 // BOT(BS=16 for typeCode or <=18, wait: use BS exact bit length)
        val typeHeader = DwgTestBitWriter().apply { writeBS(typeCode) }
        val handleStartBit = typeHeader.totalBitsWritten + 32L + dataPayload.totalBitsWritten

        val combined = DwgTestBitWriter()
        combined.writeBS(typeCode)
        combined.writeRawIntLE(handleStartBit.toInt())
        writeData(combined)
        writeHandles?.invoke(combined)
        val rawBody = combined.toByteArray()

        val frame = DwgTestBitWriter()
        frame.writeMS(rawBody.size)
        val full = frame.toByteArray() + rawBody
        return if (!isR2004Plus) full else full
    }

    private fun writeCommonEntityHeader(w: DwgTestBitWriter, handle: Long, aciColor: Int, isR2004: Boolean) {
        w.writeHandle(0, handle)
        w.writeBS(0) // eedSize
        w.writeBit(0) // graphicPresent = 0
        w.writeBB(2) // entMode = 2 (model space, no explicit owner handle)
        w.writeBL(0) // numReactors
        if (isR2004) w.writeBit(1) // xdicMissing = 1
        w.writeBit(1) // nolinks = 1
        // CMC
        w.writeBS(aciColor)
        if (isR2004) {
            w.writeBL(0)
            w.writeRawByte(0)
        }
        w.writeBD(1.0) // linetype scale
        w.writeBB(0) // ltypeFlags
        w.writeBB(0) // plotFlags
        w.writeBS(0) // invisibility
        w.writeRawByte(0) // lineweight
    }

    private fun encodeHandlesSection(entries: List<Pair<Long, Long>>): ByteArray {
        val payload = ByteArrayOutputStream()
        var lastH = 0L
        var lastO = 0L
        for ((h, o) in entries) {
            writeUmc(payload, h - lastH)
            writeMc(payload, o - lastO)
            lastH = h
            lastO = o
        }
        val body = payload.toByteArray()
        val blockSize = body.size + 2 // +2 CRC
        val out = ByteArrayOutputStream()
        out.write((blockSize ushr 8) and 0xFF)
        out.write(blockSize and 0xFF)
        out.write(body)
        out.write(0)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeUmc(out: ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v > 0) b = b or 0x80
            out.write(b)
        } while (v > 0)
    }

    private fun writeMc(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x40L) {
            out.write(((v and 0x7FL).toInt()) or 0x80)
            v = v ushr 7
        }
        out.write((v and 0x3FL).toInt())
    }

    private fun buildSyntheticR2000DwgBytes(): ByteArray {
        val buf = ByteArray(4096)
        "AC1015".toByteArray().copyInto(buf, 0)

        val layerOff = 0x100
        val lineOff = 0x200
        val circleOff = 0x300
        val arcOff = 0x400
        val polyOff = 0x500
        val handlesOff = 0x800

        // Layer (51), handle 2, name "WALLS"
        val layerBytes = buildDwgObjectFrame(51, isR2004Plus = false, writeData = { w ->
            w.writeHandle(0, 2L)
            w.writeBS(0) // eed
            w.writeBL(0) // numReactors
            w.writeTV("WALLS")
            w.writeBit(0)
            w.writeBS(0)
            w.writeBit(0)
            w.writeBS(0) // flags (on, unlocked, unfrozen)
            w.writeBS(1) // ACI red
        })
        layerBytes.copyInto(buf, layerOff)

        // Line (19), handle 3, layer 2
        val lineBytes = buildDwgObjectFrame(19, isR2004Plus = false, writeData = { w ->
            writeCommonEntityHeader(w, 3L, 256, isR2004 = false)
            w.writeBit(1) // zIsZero = 1
            w.writeRawDoubleLE(10.0)
            w.writeBB(3); w.writeRawDoubleLE(110.0)
            w.writeRawDoubleLE(20.0)
            w.writeBB(3); w.writeRawDoubleLE(70.0)
        }, writeHandles = { w ->
            w.writeHandle(0, 0L) // xdic
            w.writeHandle(0, 2L) // layer WALLS
        })
        lineBytes.copyInto(buf, lineOff)

        // Circle (18), handle 4, layer 2
        val circleBytes = buildDwgObjectFrame(18, isR2004Plus = false, writeData = { w ->
            writeCommonEntityHeader(w, 4L, 3, isR2004 = false)
            w.write3BD(50.0, 50.0)
            w.writeBD(15.0)
        }, writeHandles = { w ->
            w.writeHandle(0, 0L)
            w.writeHandle(0, 2L)
        })
        circleBytes.copyInto(buf, circleOff)

        // Arc (17), handle 5, layer 2
        val arcBytes = buildDwgObjectFrame(17, isR2004Plus = false, writeData = { w ->
            writeCommonEntityHeader(w, 5L, 5, isR2004 = false)
            w.write3BD(0.0, 0.0)
            w.writeBD(25.0)
            w.writeBit(1) // BT=0
            w.writeBit(1) // BE=default
            w.writeBD(0.0)
            w.writeBD(Math.PI / 2.0)
        }, writeHandles = { w ->
            w.writeHandle(0, 0L)
            w.writeHandle(0, 2L)
        })
        arcBytes.copyInto(buf, arcOff)

        // LWPolyline (77), handle 6, layer 2
        val polyBytes = buildDwgObjectFrame(77, isR2004Plus = false, writeData = { w ->
            writeCommonEntityHeader(w, 6L, 4, isR2004 = false)
            w.writeBS(512) // closed flag
            w.writeBL(3)
            w.writeRawDoubleLE(0.0); w.writeRawDoubleLE(0.0)
            w.writeBB(3); w.writeRawDoubleLE(40.0); w.writeBB(3); w.writeRawDoubleLE(0.0)
            w.writeBB(3); w.writeRawDoubleLE(40.0); w.writeBB(3); w.writeRawDoubleLE(40.0)
        }, writeHandles = { w ->
            w.writeHandle(0, 0L)
            w.writeHandle(0, 2L)
        })
        polyBytes.copyInto(buf, polyOff)

        val handlesSection = encodeHandlesSection(
            listOf(
                2L to layerOff.toLong(),
                3L to lineOff.toLong(),
                4L to circleOff.toLong(),
                5L to arcOff.toLong(),
                6L to polyOff.toLong()
            )
        )
        handlesSection.copyInto(buf, handlesOff)

        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x15, 1) // 1 section locator
        buf[0x19] = 2 // record 2 = AcDb:Handles
        bb.putInt(0x1A, handlesOff)
        bb.putInt(0x1E, handlesSection.size)
        return buf
    }

    private fun buildSyntheticR2004DwgBytes(): ByteArray {
        // Object stream at local offsets 0x00 and 0x80
        val lineBytes = buildDwgObjectFrame(19, isR2004Plus = true, writeData = { w ->
            writeCommonEntityHeader(w, 2L, 1, isR2004 = true)
            w.writeBit(1)
            w.writeRawDoubleLE(5.0)
            w.writeBB(3); w.writeRawDoubleLE(85.0)
            w.writeRawDoubleLE(10.0)
            w.writeBB(3); w.writeRawDoubleLE(40.0)
        })
        val circleBytes = buildDwgObjectFrame(18, isR2004Plus = true, writeData = { w ->
            writeCommonEntityHeader(w, 3L, 2, isR2004 = true)
            w.write3BD(30.0, 30.0)
            w.writeBD(12.5)
        })

        val objStream = ByteArray(256)
        lineBytes.copyInto(objStream, 0)
        circleBytes.copyInto(objStream, 128)

        val handlesSection = encodeHandlesSection(listOf(2L to 0L, 3L to 128L))

        // Lay out data pages starting sequentially from file offset 0x100:
        // page 1 = AcDb:Handles, page 2 = AcDb:AcDbObjects, page 3 = SectionMap, then SectionPageMap at end
        val p1Off = 0x100
        val p1Size = 32 + handlesSection.size
        val p2Off = p1Off + p1Size
        val p2Size = 32 + objStream.size
        val secMapOff = p2Off + p2Size

        // Build Section Map (2 sections: AcDb:Handles, AcDb:AcDbObjects)
        val secMapBody = ByteBuffer.allocate(20 + 2 * (96 + 16)).order(ByteOrder.LITTLE_ENDIAN)
        secMapBody.putInt(0, 2) // numSections
        var pos = 20
        // Section 1: AcDb:Handles
        secMapBody.putLong(pos, handlesSection.size.toLong())
        secMapBody.putInt(pos + 8, 1)
        secMapBody.putInt(pos + 12, 0x7400)
        secMapBody.putInt(pos + 20, 1) // uncompressed
        "AcDb:Handles".toByteArray().copyInto(secMapBody.array(), pos + 32)
        pos += 96
        secMapBody.putInt(pos, 1) // pageNum = 1
        secMapBody.putInt(pos + 4, handlesSection.size)
        secMapBody.putLong(pos + 8, 0L)
        pos += 16

        // Section 2: AcDb:AcDbObjects
        secMapBody.putLong(pos, objStream.size.toLong())
        secMapBody.putInt(pos + 8, 1)
        secMapBody.putInt(pos + 12, 0x7400)
        secMapBody.putInt(pos + 20, 1) // uncompressed
        "AcDb:AcDbObjects".toByteArray().copyInto(secMapBody.array(), pos + 32)
        pos += 96
        secMapBody.putInt(pos, 2) // pageNum = 2
        secMapBody.putInt(pos + 4, objStream.size)
        secMapBody.putLong(pos + 8, 0L)

        val secMapRaw = secMapBody.array()
        val secMapPageSize = 20 + secMapRaw.size
        val pageMapOff = secMapOff + secMapPageSize

        // Section Page Map entries: (1, p1Size), (2, p2Size), (3, secMapPageSize)
        val pageMapRaw = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(1); putInt(p1Size)
            putInt(2); putInt(p2Size)
            putInt(3); putInt(secMapPageSize)
        }.array()
        val pageMapPageSize = 20 + pageMapRaw.size

        val out = ByteArray(pageMapOff + pageMapPageSize + 64)
        "AC1018".toByteArray().copyInto(out, 0)

        // Write masked data pages 1 & 2
        fun writeDataPage(fileOff: Int, payload: ByteArray) {
            val mask = 0x4164536b xor fileOff
            val hdr = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            hdr.putInt(12, payload.size)
            for (k in 0 until 8) {
                hdr.putInt(k * 4, hdr.getInt(k * 4) xor mask)
            }
            hdr.array().copyInto(out, fileOff)
            payload.copyInto(out, fileOff + 32)
        }
        writeDataPage(p1Off, handlesSection)
        writeDataPage(p2Off, objStream)

        // Write system pages (uncompressed: compType = 1)
        fun writeSysPage(fileOff: Int, tag: Int, payload: ByteArray) {
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(fileOff, tag)
            bb.putInt(fileOff + 4, payload.size)
            bb.putInt(fileOff + 8, payload.size)
            bb.putInt(fileOff + 12, 1)
            payload.copyInto(out, fileOff + 20)
        }
        writeSysPage(secMapOff, 0x4163003B, secMapRaw)
        writeSysPage(pageMapOff, 0x41630E3B, pageMapRaw)

        // Write & LCG-encrypt 0x80..0xEC header
        val plainHdr = ByteArray(0x6C)
        "AcFssFcAJMB".toByteArray().copyInto(plainHdr, 0)
        ByteBuffer.wrap(plainHdr).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0x58, (pageMapOff - 0x100).toLong())
            putInt(0x60, 3) // sectionMapId = page 3
        }
        var seed = 1
        for (i in plainHdr.indices) {
            seed = seed * 0x343fd + 0x269ec3
            out[0x80 + i] = (plainHdr[i].toInt() xor (seed ushr 16)).toByte()
        }
        return out
    }
}


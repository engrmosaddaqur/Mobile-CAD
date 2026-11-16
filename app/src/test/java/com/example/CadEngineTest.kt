package com.example

import android.graphics.Bitmap
import com.example.cad.engine.ActiveTool
import com.example.cad.engine.CommandProcessor
import com.example.cad.engine.DrawTool
import com.example.cad.engine.ParsedCliInput
import com.example.cad.geometry.GeometryEngine
import com.example.cad.geometry.GeometryOperations
import com.example.cad.geometry.SnapEngine
import com.example.cad.io.CadExportEngine
import com.example.cad.io.DwgEngine
import com.example.cad.io.DwgOperationResult
import com.example.cad.io.DwgSdkBridge
import com.example.cad.io.DxfParser
import com.example.cad.io.DxfWriter
import com.example.cad.io.SampleDrawings
import com.example.cad.model.Bounds2D
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.CadUnits
import com.example.cad.model.DimensionType
import com.example.cad.model.OsnapType
import com.example.cad.model.SnapSettings
import com.example.cad.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
}

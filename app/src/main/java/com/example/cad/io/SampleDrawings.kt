package com.example.cad.io

import com.example.cad.model.CadBlockDefinition
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.CadUnits
import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object SampleDrawings {

    fun blankDrawing(name: String, projectGroup: String = "Default Project", units: CadUnits = CadUnits.MILLIMETERS): CadDocument {
        return CadDocument(
            name = name,
            projectGroup = projectGroup,
            units = units
        )
    }

    fun architecturalStudioFloorPlan(): CadDocument {
        val layers = listOf(
            CadLayer("0", colorArgb = 0xFFFFFFFF.toInt(), aciColor = 7, lineWeightMm = 0.25f),
            CadLayer("WALLS", colorArgb = 0xFFFFFFFF.toInt(), aciColor = 7, lineWeightMm = 0.45f),
            CadLayer("DOORS_WINDOWS", colorArgb = 0xFF00E5FF.toInt(), aciColor = 4, lineWeightMm = 0.20f),
            CadLayer("FURNITURE", colorArgb = 0xFFFFB300.toInt(), aciColor = 40, lineWeightMm = 0.18f),
            CadLayer("DIMENSIONS", colorArgb = 0xFF00E676.toInt(), aciColor = 3, lineWeightMm = 0.15f),
            CadLayer("CENTERLINES", colorArgb = 0xFFFF5252.toInt(), aciColor = 1, lineType = CadLineType.CENTER, lineWeightMm = 0.13f)
        )

        val doorBlock = CadBlockDefinition(
            name = "DOOR_900",
            basePoint = Vec2.ZERO,
            entities = listOf(
                CadLine(layerName = "DOORS_WINDOWS", start = Vec2(0.0, 0.0), end = Vec2(0.0, 9.0)),
                CadArc(layerName = "DOORS_WINDOWS", center = Vec2(0.0, 0.0), radius = 9.0, startAngleDeg = 0.0, endAngleDeg = 90.0)
            )
        )

        val entities = mutableListOf<CadEntity>(
            // Outer structural walls (in dm/scale: 120 x 80 units = 12.0m x 8.0m)
            CadPolyline(
                layerName = "WALLS",
                points = listOf(Vec2(0.0, 0.0), Vec2(120.0, 0.0), Vec2(120.0, 80.0), Vec2(0.0, 80.0)),
                isClosed = true
            ),
            CadPolyline(
                layerName = "WALLS",
                points = listOf(Vec2(3.0, 3.0), Vec2(117.0, 3.0), Vec2(117.0, 77.0), Vec2(3.0, 77.0)),
                isClosed = true
            ),
            // Interior partition walls
            CadLine(layerName = "WALLS", start = Vec2(65.0, 3.0), end = Vec2(65.0, 48.0)),
            CadLine(layerName = "WALLS", start = Vec2(67.0, 3.0), end = Vec2(67.0, 48.0)),
            CadLine(layerName = "WALLS", start = Vec2(65.0, 48.0), end = Vec2(117.0, 48.0)),
            CadLine(layerName = "WALLS", start = Vec2(65.0, 50.0), end = Vec2(117.0, 50.0)),
            // Door swing block
            CadBlockReference(layerName = "DOORS_WINDOWS", blockName = "DOOR_900", insertionPoint = Vec2(65.0, 34.0)),
            // Windows on top & left walls
            CadLine(layerName = "DOORS_WINDOWS", start = Vec2(20.0, 78.5), end = Vec2(50.0, 78.5)),
            CadLine(layerName = "DOORS_WINDOWS", start = Vec2(80.0, 78.5), end = Vec2(105.0, 78.5)),
            // Conference table & chairs
            CadPolyline(
                layerName = "FURNITURE",
                points = listOf(Vec2(18.0, 26.0), Vec2(48.0, 26.0), Vec2(48.0, 46.0), Vec2(18.0, 46.0)),
                isClosed = true
            ),
            CadCircle(layerName = "FURNITURE", center = Vec2(25.0, 21.0), radius = 2.6),
            CadCircle(layerName = "FURNITURE", center = Vec2(41.0, 21.0), radius = 2.6),
            CadCircle(layerName = "FURNITURE", center = Vec2(25.0, 51.0), radius = 2.6),
            CadCircle(layerName = "FURNITURE", center = Vec2(41.0, 51.0), radius = 2.6),
            // Axis centerline
            CadLine(layerName = "CENTERLINES", start = Vec2(-8.0, 40.0), end = Vec2(128.0, 40.0)),
            // Labels
            CadText(layerName = "FURNITURE", position = Vec2(20.0, 62.0), text = "DESIGN STUDIO", height = 3.5),
            CadText(layerName = "FURNITURE", position = Vec2(76.0, 24.0), text = "LAB BAY", height = 3.2),
            // Dimensions
            CadDimension(
                layerName = "DIMENSIONS",
                dimType = DimensionType.LINEAR,
                p1 = Vec2(0.0, 0.0),
                p2 = Vec2(120.0, 0.0),
                dimLinePoint = Vec2(60.0, -10.0)
            ),
            CadDimension(
                layerName = "DIMENSIONS",
                dimType = DimensionType.LINEAR,
                p1 = Vec2(0.0, 0.0),
                p2 = Vec2(0.0, 80.0),
                dimLinePoint = Vec2(-12.0, 40.0)
            )
        )

        return CadDocument(
            name = "Studio_Floor_Plan",
            projectGroup = "Architectural Samples",
            units = CadUnits.CENTIMETERS,
            layers = layers,
            currentLayerName = "WALLS",
            blocks = mapOf("DOOR_900" to doorBlock),
            entities = entities
        )
    }

    fun mechanicalGearBracket(): CadDocument {
        val layers = listOf(
            CadLayer("0", colorArgb = 0xFFFFFFFF.toInt(), aciColor = 7, lineWeightMm = 0.30f),
            CadLayer("PROFILE", colorArgb = 0xFF00E5FF.toInt(), aciColor = 4, lineWeightMm = 0.35f),
            CadLayer("CENTERLINES", colorArgb = 0xFFFF5252.toInt(), aciColor = 1, lineType = CadLineType.CENTER, lineWeightMm = 0.15f),
            CadLayer("DIMENSIONS", colorArgb = 0xFFFFB300.toInt(), aciColor = 40, lineWeightMm = 0.18f)
        )
        val entities = mutableListOf<CadEntity>()
        val center = Vec2(0.0, 0.0)
        // Concentric bores
        entities.add(CadCircle(layerName = "PROFILE", center = center, radius = 14.0))
        entities.add(CadCircle(layerName = "PROFILE", center = center, radius = 24.0))
        entities.add(CadCircle(layerName = "CENTERLINES", center = center, radius = 34.0))

        // 16-tooth spur gear outline polyline
        val teeth = 16
        val rootR = 40.0
        val tipR = 47.0
        val pts = mutableListOf<Vec2>()
        for (i in 0 until teeth) {
            val baseA = 2.0 * PI * i / teeth
            val step = 2.0 * PI / teeth
            pts.add(Vec2(rootR * cos(baseA), rootR * sin(baseA)))
            pts.add(Vec2(tipR * cos(baseA + step * 0.2), tipR * sin(baseA + step * 0.2)))
            pts.add(Vec2(tipR * cos(baseA + step * 0.55), tipR * sin(baseA + step * 0.55)))
            pts.add(Vec2(rootR * cos(baseA + step * 0.75), rootR * sin(baseA + step * 0.75)))
        }
        entities.add(CadPolyline(layerName = "PROFILE", points = pts, isClosed = true))

        // 6 bolt holes on pitch circle
        for (i in 0 until 6) {
            val a = 2.0 * PI * i / 6.0
            entities.add(CadCircle(layerName = "PROFILE", center = Vec2(34.0 * cos(a), 34.0 * sin(a)), radius = 3.2))
        }

        // Crosshairs
        entities.add(CadLine(layerName = "CENTERLINES", start = Vec2(-56.0, 0.0), end = Vec2(56.0, 0.0)))
        entities.add(CadLine(layerName = "CENTERLINES", start = Vec2(0.0, -56.0), end = Vec2(0.0, 56.0)))

        // Radial & Diameter Dimensions
        entities.add(
            CadDimension(
                layerName = "DIMENSIONS",
                dimType = DimensionType.DIAMETER,
                p1 = center,
                p2 = Vec2(14.0 * cos(PI / 4), 14.0 * sin(PI / 4)),
                dimLinePoint = Vec2(28.0, 28.0)
            )
        )
        entities.add(
            CadDimension(
                layerName = "DIMENSIONS",
                dimType = DimensionType.RADIUS,
                p1 = center,
                p2 = Vec2(tipR * cos(-PI / 6), tipR * sin(-PI / 6)),
                dimLinePoint = Vec2(58.0, -34.0)
            )
        )

        return CadDocument(
            name = "Spur_Gear_Assembly",
            projectGroup = "Mechanical Samples",
            units = CadUnits.MILLIMETERS,
            layers = layers,
            currentLayerName = "PROFILE",
            entities = entities
        )
    }

    fun allSampleTemplates(): List<CadDocument> = listOf(
        architecturalStudioFloorPlan(),
        mechanicalGearBracket()
    )
}

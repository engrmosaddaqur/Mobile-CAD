package com.example.cad.model

import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Double) = Vec2(x * scalar, y * scalar)
    operator fun div(scalar: Double) = Vec2(x / scalar, y / scalar)

    fun dot(other: Vec2): Double = x * other.x + y * other.y
    fun cross(other: Vec2): Double = x * other.y - y * other.x
    fun length(): Double = hypot(x, y)
    fun lengthSquared(): Double = x * x + y * y
    fun distanceTo(other: Vec2): Double = hypot(x - other.x, y - other.y)

    fun normalized(): Vec2 {
        val len = length()
        return if (len > 1e-12) Vec2(x / len, y / len) else Vec2(1.0, 0.0)
    }

    fun perpendicular(): Vec2 = Vec2(-y, x)

    fun angleRad(): Double = atan2(y, x)
    fun angleDeg(): Double {
        var deg = Math.toDegrees(atan2(y, x))
        if (deg < 0.0) deg += 360.0
        return deg
    }

    fun rotateAround(pivot: Vec2, angleRad: Double): Vec2 {
        val dx = x - pivot.x
        val dy = y - pivot.y
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec2(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
    }

    fun scaleAround(pivot: Vec2, factor: Double): Vec2 =
        Vec2(pivot.x + (x - pivot.x) * factor, pivot.y + (y - pivot.y) * factor)

    fun mirrorAcross(a: Vec2, b: Vec2): Vec2 {
        val ab = b - a
        val lenSq = ab.lengthSquared()
        if (lenSq < 1e-12) return this
        val t = (this - a).dot(ab) / lenSq
        val proj = a + ab * t
        return proj * 2.0 - this
    }

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun fromPolar(origin: Vec2, distance: Double, angleDeg: Double): Vec2 {
            val rad = Math.toRadians(angleDeg)
            return Vec2(origin.x + distance * cos(rad), origin.y + distance * sin(rad))
        }
    }
}

data class Bounds2D(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val center: Vec2 get() = Vec2((minX + maxX) * 0.5, (minY + maxY) * 0.5)

    fun intersects(other: Bounds2D): Boolean {
        return maxX >= other.minX && minX <= other.maxX &&
            maxY >= other.minY && minY <= other.maxY
    }

    fun contains(other: Bounds2D): Boolean {
        return other.minX >= minX && other.maxX <= maxX &&
            other.minY >= minY && other.maxY <= maxY
    }

    fun containsPoint(p: Vec2): Boolean {
        return p.x in minX..maxX && p.y in minY..maxY
    }

    fun expand(margin: Double): Bounds2D =
        Bounds2D(minX - margin, minY - margin, maxX + margin, maxY + margin)

    fun union(other: Bounds2D): Bounds2D = Bounds2D(
        minX = min(minX, other.minX),
        minY = min(minY, other.minY),
        maxX = max(maxX, other.maxX),
        maxY = max(maxY, other.maxY)
    )

    companion object {
        fun fromPoints(points: Iterable<Vec2>): Bounds2D {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return if (any) Bounds2D(minX, minY, maxX, maxY) else Bounds2D(-50.0, -50.0, 50.0, 50.0)
        }
    }
}

enum class CadLineType(val dxfName: String, val displayName: String, val dashPatternPx: FloatArray?) {
    CONTINUOUS("CONTINUOUS", "Continuous", null),
    DASHED("DASHED", "Dashed", floatArrayOf(18f, 10f)),
    DOTTED("DOTTED", "Dotted", floatArrayOf(4f, 8f)),
    DASHDOT("DASHDOT", "Dash-Dot", floatArrayOf(18f, 7f, 4f, 7f)),
    CENTER("CENTER", "Center", floatArrayOf(28f, 8f, 8f, 8f)),
    HIDDEN("HIDDEN", "Hidden", floatArrayOf(10f, 8f));

    companion object {
        fun fromDxfName(name: String): CadLineType {
            val upper = name.trim().uppercase()
            return entries.firstOrNull { upper.contains(it.dxfName) } ?: CONTINUOUS
        }
    }
}

enum class CadUnits(
    val dxfCode: Int,
    val shortLabel: String,
    val displayName: String,
    val mmFactor: Double
) {
    MILLIMETERS(4, "mm", "Millimeters (mm)", 1.0),
    CENTIMETERS(5, "cm", "Centimeters (cm)", 10.0),
    METERS(6, "m", "Meters (m)", 1000.0),
    INCHES(1, "in", "Inches (in)", 25.4),
    FEET(2, "ft", "Feet (ft)", 304.8);

    companion object {
        fun fromDxfCode(code: Int): CadUnits = entries.firstOrNull { it.dxfCode == code } ?: MILLIMETERS
    }
}

enum class DimensionTextPosition(val label: String) {
    ABOVE("Above Line"),
    CENTERED("Centered"),
    OUTSIDE("Outside")
}

enum class DimensionType(val commandName: String, val label: String) {
    LINEAR("DIMLINEAR", "Linear"),
    ALIGNED("DIMALIGNED", "Aligned"),
    ANGULAR("DIMANGULAR", "Angular"),
    RADIUS("DIMRADIUS", "Radius"),
    DIAMETER("DIMDIAMETER", "Diameter"),
    ARC_LENGTH("DIMARC", "Arc Length"),
    CONTINUE("DIMCONTINUE", "Continue"),
    BASELINE("DIMBASELINE", "Baseline"),
    LEADER("LEADER", "Leader"),
    MULTILEADER("MLEADER", "Multileader")
}

data class DimensionStyle(
    val name: String = "ISO-25",
    val textHeight: Double = 3.5,
    val arrowSize: Double = 2.8,
    val lineColorArgb: Int = 0xFF00E5FF.toInt(),
    val textColorArgb: Int = 0xFFFFB300.toInt(),
    val decimalPrecision: Int = 2,
    val units: CadUnits = CadUnits.MILLIMETERS,
    val scaleFactor: Double = 1.0,
    val textPosition: DimensionTextPosition = DimensionTextPosition.ABOVE
)

data class CadLayer(
    val name: String,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val aciColor: Int = 7,
    val lineType: CadLineType = CadLineType.CONTINUOUS,
    val lineWeightMm: Float = 0.25f,
    val isOn: Boolean = true,
    val isFrozen: Boolean = false,
    val isLocked: Boolean = false,
    val isPlottable: Boolean = true
)

data class CadLayout(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isModelSpace: Boolean,
    val paperWidthMm: Double = 297.0,
    val paperHeightMm: Double = 210.0,
    val viewportCenter: Vec2 = Vec2(0.0, 0.0),
    val viewportScale: Double = 1.0,
    val titleBlockTitle: String = "ENGINEERING DRAWING",
    val titleBlockDrawingNo: String = "CAD-001",
    val titleBlockAuthor: String = "MOBILE CAD"
)

data class CadBlockDefinition(
    val name: String,
    val basePoint: Vec2 = Vec2.ZERO,
    val entities: List<CadEntity>
)

enum class OsnapType(val label: String, val shortCode: String) {
    ENDPOINT("Endpoint", "END"),
    MIDPOINT("Midpoint", "MID"),
    CENTER("Center", "CEN"),
    INTERSECTION("Intersection", "INT"),
    PERPENDICULAR("Perpendicular", "PER"),
    TANGENT("Tangent", "TAN"),
    NEAREST("Nearest", "NEA"),
    QUADRANT("Quadrant", "QUA"),
    EXTENSION("Extension", "EXT")
}

data class SnapSettings(
    val osnapEnabled: Boolean = true,
    val enabledOsnaps: Set<OsnapType> = setOf(
        OsnapType.ENDPOINT,
        OsnapType.MIDPOINT,
        OsnapType.CENTER,
        OsnapType.INTERSECTION,
        OsnapType.PERPENDICULAR,
        OsnapType.TANGENT,
        OsnapType.NEAREST,
        OsnapType.QUADRANT,
        OsnapType.EXTENSION
    ),
    val gridVisible: Boolean = true,
    val gridSpacing: Double = 10.0,
    val gridSnapEnabled: Boolean = false,
    val orthoMode: Boolean = false,
    val polarTracking: Boolean = true,
    val polarIncrementDeg: Double = 45.0,
    val dynamicInput: Boolean = true
)

enum class CanvasBackgroundTheme(val label: String, val backgroundArgb: Long, val isDarkCanvas: Boolean) {
    CAD_BLACK("Obsidian Black", 0xFF080C12, true),
    BLUEPRINT_NAVY("Blueprint Navy", 0xFF0A192F, true),
    SLATE_GRAY("Drafting Charcoal", 0xFF1C2128, true),
    PAPER_WHITE("Drafting Paper", 0xFFFDFEFE, false)
}

enum class CadSourceFormat(val extension: String, val label: String) {
    DXF("dxf", "DXF"),
    DWG("dwg", "DWG"),
    MCAD("dxf", "DXF")
}

data class CadDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Drawing1",
    val projectGroup: String = "Default Project",
    val units: CadUnits = CadUnits.MILLIMETERS,
    val layers: List<CadLayer> = defaultLayers(),
    val currentLayerName: String = "0",
    val blocks: Map<String, CadBlockDefinition> = emptyMap(),
    val entities: List<CadEntity> = emptyList(),
    val layouts: List<CadLayout> = defaultLayouts(),
    val activeLayoutId: String = "model",
    val dimensionStyle: DimensionStyle = DimensionStyle(),
    val sourceFormat: CadSourceFormat = CadSourceFormat.DXF,
    val sourceUri: String? = null,
    val lastModifiedMs: Long = System.currentTimeMillis(),
    val isDirty: Boolean = false,
    val importWarnings: List<String> = emptyList()
) {
    val activeLayout: CadLayout
        get() = layouts.firstOrNull { it.id == activeLayoutId } ?: layouts.first()

    fun layerByName(name: String): CadLayer =
        layers.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: layers.first()

    companion object {
        fun defaultLayers(): List<CadLayer> = listOf(
            CadLayer("0", colorArgb = 0xFFFFFFFF.toInt(), aciColor = 7, lineWeightMm = 0.25f),
            CadLayer("DIMENSIONS", colorArgb = 0xFF00E5FF.toInt(), aciColor = 4, lineWeightMm = 0.18f),
            CadLayer("CENTERLINES", colorArgb = 0xFFFF5252.toInt(), aciColor = 1, lineType = CadLineType.CENTER, lineWeightMm = 0.15f),
            CadLayer("HIDDEN", colorArgb = 0xFFFFB300.toInt(), aciColor = 2, lineType = CadLineType.DASHED, lineWeightMm = 0.18f),
            CadLayer("CONSTRUCTION", colorArgb = 0xFF64B5F6.toInt(), aciColor = 5, lineType = CadLineType.DOTTED, lineWeightMm = 0.13f)
        )

        fun defaultLayouts(): List<CadLayout> = listOf(
            CadLayout(id = "model", name = "Model", isModelSpace = true),
            CadLayout(id = "layout_1", name = "Layout 1", isModelSpace = false, paperWidthMm = 297.0, paperHeightMm = 210.0),
            CadLayout(id = "layout_2", name = "Layout 2", isModelSpace = false, paperWidthMm = 420.0, paperHeightMm = 297.0)
        )
    }
}

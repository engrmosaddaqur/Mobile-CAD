package com.example.cad.engine

import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2

enum class WorkspacePanelTab(val label: String) {
    DRAW("Draw"),
    MODIFY("Modify"),
    DIMENSION("Dimension"),
    LAYERS("Layers"),
    MEASURE("Measure"),
    PROPERTIES("Properties")
}

enum class DrawTool(val commandName: String, val label: String) {
    LINE("LINE", "Line"),
    POLYLINE("PLINE", "Polyline"),
    TRIANGLE("TRIANGLE", "Triangle"),
    RECTANGLE("RECTANGLE", "Rectangle"),
    CIRCLE("CIRCLE", "Circle"),
    POLYGON("POLYGON", "Polygon"),
    ARC("ARC", "Arc"),
    ELLIPSE("ELLIPSE", "Ellipse"),
    SPLINE("SPLINE", "Spline"),
    POINT("POINT", "Point"),
    RAY("RAY", "Ray"),
    XLINE("XLINE", "XLine")
}

enum class ModifyTool(val commandName: String, val label: String, val isImmediateAction: Boolean = false) {
    MOVE("MOVE", "Move"),
    COPY("COPY", "Copy"),
    ROTATE("ROTATE", "Rotate"),
    MIRROR("MIRROR", "Mirror"),
    OFFSET("OFFSET", "Offset"),
    TRIM("TRIM", "Trim"),
    EXTEND("EXTEND", "Extend"),
    STRETCH("STRETCH", "Stretch"),
    FILLET("FILLET", "Fillet"),
    CHAMFER("CHAMFER", "Chamfer"),
    SCALE("SCALE", "Scale"),
    ARRAY("ARRAY", "Array"),
    EXPLODE("EXPLODE", "Explode", isImmediateAction = true),
    JOIN("JOIN", "Join", isImmediateAction = true),
    BREAK("BREAK", "Break"),
    ERASE("ERASE", "Erase", isImmediateAction = true),
    DELETE("DELETE", "Delete", isImmediateAction = true),
    UNDO("UNDO", "Undo", isImmediateAction = true),
    REDO("REDO", "Redo", isImmediateAction = true)
}

enum class MeasureTool(val commandName: String, val label: String) {
    DISTANCE("DIST", "Distance"),
    AREA("AREA", "Area"),
    PERIMETER("PERIMETER", "Perimeter"),
    RADIUS("MRADIUS", "Radius"),
    DIAMETER("MDIAMETER", "Diameter"),
    ANGLE("MANGLE", "Angle"),
    COORDINATE("ID", "Coordinate")
}

sealed class ActiveTool {
    data object Select : ActiveTool()
    data object Pan : ActiveTool()
    data object ZoomWindow : ActiveTool()
    data object RectSelect : ActiveTool()
    data class Draw(val tool: DrawTool) : ActiveTool()
    data class Modify(val tool: ModifyTool) : ActiveTool()
    data class Dimension(val type: DimensionType) : ActiveTool()
    data class Measure(val tool: MeasureTool) : ActiveTool()

    val displayName: String
        get() = when (this) {
            Select -> "SELECT"
            Pan -> "PAN"
            ZoomWindow -> "ZOOM WINDOW"
            RectSelect -> "WINDOW/CROSSING SELECT"
            is Draw -> tool.commandName
            is Modify -> tool.commandName
            is Dimension -> type.commandName
            is Measure -> tool.commandName
        }
}

data class ToolNumericConfig(
    val polygonSides: Int = 6,
    val filletRadius: Double = 5.0,
    val chamferDist1: Double = 5.0,
    val chamferDist2: Double = 5.0,
    val offsetDistance: Double = 10.0,
    val rotateAngleDeg: Double = 90.0,
    val scaleFactor: Double = 1.5,
    val arrayPolarMode: Boolean = false,
    val arrayRows: Int = 2,
    val arrayCols: Int = 3,
    val arrayRowSpacing: Double = 25.0,
    val arrayColSpacing: Double = 25.0,
    val arrayPolarCount: Int = 6,
    val arrayPolarAngleDeg: Double = 360.0
)

data class MeasurementReadout(
    val title: String,
    val primaryValue: String,
    val secondaryDetails: String = ""
)

data class CommandHistoryEntry(
    val text: String,
    val isError: Boolean = false,
    val isCommand: Boolean = false
)

data class ToolSession(
    val collectedPoints: List<Vec2> = emptyList(),
    val targetEntityIds: List<String> = emptyList(),
    val draggingGrip: Pair<String, Int>? = null,
    val stretchWindow: Pair<Vec2, Vec2>? = null,
    val lastBaselineOrigin: Vec2? = null,
    val lastContinueEnd: Vec2? = null
)

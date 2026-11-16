package com.example.cad.engine

import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import kotlin.math.cos
import kotlin.math.sin

sealed class ParsedCliInput {
    data class SwitchTool(val tool: ActiveTool, val promptMessage: String) : ParsedCliInput()
    data class PointCoordinate(val point: Vec2, val rawLabel: String) : ParsedCliInput()
    data class ScalarValue(val value: Double) : ParsedCliInput()
    data object ConfirmFinish : ParsedCliInput()
    data object ClosePolyline : ParsedCliInput()
    data object CancelTool : ParsedCliInput()
    data class ZoomCommand(val mode: String) : ParsedCliInput()
    data class ToggleFlag(val flag: String) : ParsedCliInput()
    data object SelectAll : ParsedCliInput()
    data object DeselectAll : ParsedCliInput()
    data object SaveDrawing : ParsedCliInput()
    data object OpenLayers : ParsedCliInput()
    data class UndoCommand(val steps: Int = 1) : ParsedCliInput()
    data class RedoCommand(val steps: Int = 1) : ParsedCliInput()
    data object Help : ParsedCliInput()
    data class Unknown(val raw: String) : ParsedCliInput()
}

object CommandProcessor {

    val allKnownCommands: List<String> = listOf(
        "LINE", "PLINE", "TRIANGLE", "RECTANGLE", "CIRCLE", "POLYGON", "ARC", "ELLIPSE", "SPLINE", "POINT", "RAY", "XLINE",
        "MOVE", "COPY", "ROTATE", "MIRROR", "OFFSET", "TRIM", "EXTEND", "STRETCH", "FILLET", "CHAMFER", "SCALE", "ARRAY",
        "EXPLODE", "JOIN", "BREAK", "ERASE", "DELETE", "UNDO", "REDO",
        "DIMLINEAR", "DIMALIGNED", "DIMANGULAR", "DIMRADIUS", "DIMDIAMETER", "DIMARC", "DIMCONTINUE", "DIMBASELINE", "LEADER", "MLEADER",
        "DIST", "AREA", "PERIMETER", "ID", "ZOOM", "ZE", "ZP", "ZA", "PAN", "LAYER", "ORTHO", "GRID", "OSNAP", "SELECTALL", "DESELECT", "SAVE", "HELP"
    )

    fun parse(rawInput: String, lastPoint: Vec2?): ParsedCliInput {
        val input = rawInput.trim()
        if (input.isEmpty()) return ParsedCliInput.ConfirmFinish

        val upper = input.uppercase()

        // Single-letter or keyword CAD flow controls
        if (upper == "C" && lastPoint != null) {
            // Context-sensitive: if already drawing a polyline, 'C' can close, otherwise Circle if idle
        }
        if (upper == "CLOSE" || upper == "CL") return ParsedCliInput.ClosePolyline
        if (upper == "ESC" || upper == "CANCEL") return ParsedCliInput.CancelTool
        if (upper == "DONE" || upper == "ENTER") return ParsedCliInput.ConfirmFinish

        // Polar relative coordinate: @distance<angle
        if (input.startsWith("@") && input.contains("<")) {
            val body = input.drop(1)
            val parts = body.split("<")
            val dist = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val angleDeg = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (dist != null && angleDeg != null) {
                val base = lastPoint ?: Vec2.ZERO
                val rad = Math.toRadians(angleDeg)
                val pt = Vec2(base.x + dist * cos(rad), base.y + dist * sin(rad))
                return ParsedCliInput.PointCoordinate(pt, input)
            }
        }

        // Relative cartesian coordinate: @dx,dy
        if (input.startsWith("@") && input.contains(",")) {
            val parts = input.drop(1).split(",")
            val dx = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val dy = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (dx != null && dy != null) {
                val base = lastPoint ?: Vec2.ZERO
                return ParsedCliInput.PointCoordinate(Vec2(base.x + dx, base.y + dy), input)
            }
        }

        // Absolute cartesian coordinate: x,y
        if (input.contains(",")) {
            val parts = input.split(",")
            val x = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val y = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (x != null && y != null) {
                return ParsedCliInput.PointCoordinate(Vec2(x, y), input)
            }
        }

        if (upper.startsWith("UNDO ") || upper.startsWith("U ")) {
            val steps = upper.substringAfter(' ').trim().toIntOrNull()?.coerceIn(1, 50) ?: 1
            return ParsedCliInput.UndoCommand(steps)
        }
        if (upper.startsWith("REDO ")) {
            val steps = upper.substringAfter(' ').trim().toIntOrNull()?.coerceIn(1, 50) ?: 1
            return ParsedCliInput.RedoCommand(steps)
        }

        // Direct scalar numeric value (length, radius, angle, distance)
        input.toDoubleOrNull()?.let { return ParsedCliInput.ScalarValue(it) }

        return when (upper) {
            "L", "LINE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.LINE), "LINE: Specify first point (tap or enter X,Y)")
            "PL", "PLINE", "POLYLINE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.POLYLINE), "PLINE: Specify start point")
            "TRI", "TRIANGLE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.TRIANGLE), "TRIANGLE: Specify 3 vertices")
            "REC", "RECT", "RECTANGLE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.RECTANGLE), "RECTANGLE: Specify first corner")
            "C", "CIRCLE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.CIRCLE), "CIRCLE: Specify center point")
            "POL", "POLYGON" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.POLYGON), "POLYGON: Specify center point")
            "A", "ARC" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.ARC), "ARC: Specify center point, start radius point, and end angle")
            "EL", "ELLIPSE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.ELLIPSE), "ELLIPSE: Specify center, major axis endpoint, and minor radius")
            "SPL", "SPLINE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.SPLINE), "SPLINE: Specify control points, tap Done to finish")
            "PO", "POINT" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.POINT), "POINT: Specify point location")
            "RAY" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.RAY), "RAY: Specify origin and direction point")
            "XL", "XLINE" -> ParsedCliInput.SwitchTool(ActiveTool.Draw(DrawTool.XLINE), "XLINE: Specify origin and direction point")

            "M", "MOVE" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.MOVE), "MOVE: Specify base point, then destination point")
            "CO", "CP", "COPY" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.COPY), "COPY: Specify base point, then copy displacement point")
            "RO", "ROTATE" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.ROTATE), "ROTATE: Specify rotation pivot point")
            "MI", "MIRROR" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.MIRROR), "MIRROR: Specify first and second point of mirror line")
            "O", "OFFSET" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.OFFSET), "OFFSET: Tap entity, then tap side to offset")
            "TR", "TRIM" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.TRIM), "TRIM: Tap segment to trim against intersecting edges")
            "EX", "EXTEND" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.EXTEND), "EXTEND: Tap near line end to extend to boundary")
            "S", "STRETCH" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.STRETCH), "STRETCH: Drag selection window around vertices, then specify displacement")
            "F", "FILLET" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.FILLET), "FILLET: Tap first line, then second line")
            "CHA", "CHAMFER" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.CHAMFER), "CHAMFER: Tap first line, then second line")
            "SC", "SCALE" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.SCALE), "SCALE: Specify base point to scale selected entities")
            "AR", "ARRAY" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.ARRAY), "ARRAY: Tap canvas or set parameters to generate array")
            "X", "EXPLODE" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.EXPLODE), "EXPLODE: Exploding selected entities")
            "J", "JOIN" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.JOIN), "JOIN: Joining selected lines/polylines")
            "BR", "BREAK" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.BREAK), "BREAK: Tap entity at break point")
            "E", "ERASE" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.ERASE), "ERASE: Erased selected entities")
            "DEL", "DELETE" -> ParsedCliInput.SwitchTool(ActiveTool.Modify(ModifyTool.DELETE), "DELETE: Deleted selected entities")
            "U", "UNDO" -> ParsedCliInput.UndoCommand(1)
            "REDO" -> ParsedCliInput.RedoCommand(1)

            "DIMLINEAR", "DLI" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.LINEAR), "DIMLINEAR: Specify first and second extension origin, then dimension line location")
            "DIMALIGNED", "DAL" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.ALIGNED), "DIMALIGNED: Specify first and second point, then offset")
            "DIMANGULAR", "DAN" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.ANGULAR), "DIMANGULAR: Specify vertex, first ray, second ray")
            "DIMRADIUS", "DRA" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.RADIUS), "DIMRADIUS: Tap circle or arc")
            "DIMDIAMETER", "DDI" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.DIAMETER), "DIMDIAMETER: Tap circle or arc")
            "DIMARC", "DAR" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.ARC_LENGTH), "DIMARC: Tap arc")
            "DIMCONTINUE", "DCO" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.CONTINUE), "DIMCONTINUE: Specify next extension point")
            "DIMBASELINE", "DBA" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.BASELINE), "DIMBASELINE: Specify next extension point")
            "LEADER", "LE" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.LEADER), "LEADER: Specify arrow point, then landing point")
            "MLEADER", "MLD" -> ParsedCliInput.SwitchTool(ActiveTool.Dimension(DimensionType.MULTILEADER), "MLEADER: Specify arrow point, then callout landing point")

            "DI", "DIST" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.DISTANCE), "DIST: Specify first and second point")
            "AREA", "AA" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.AREA), "AREA: Tap boundary vertices, tap Done to compute area & perimeter")
            "PERIMETER" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.PERIMETER), "PERIMETER: Tap entity or vertices")
            "MRADIUS" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.RADIUS), "RADIUS: Tap circle or arc")
            "MDIAMETER" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.DIAMETER), "DIAMETER: Tap circle or arc")
            "MANGLE" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.ANGLE), "ANGLE: Specify vertex and two legs")
            "ID" -> ParsedCliInput.SwitchTool(ActiveTool.Measure(MeasureTool.COORDINATE), "ID: Tap any point to inspect coordinates")

            "Z", "ZOOM", "ZE" -> ParsedCliInput.ZoomCommand("EXTENTS")
            "ZW" -> ParsedCliInput.SwitchTool(ActiveTool.ZoomWindow, "ZOOM WINDOW: Drag rectangle across area to zoom")
            "ZP" -> ParsedCliInput.ZoomCommand("PREVIOUS")
            "ZA" -> ParsedCliInput.ZoomCommand("ALL")
            "P", "PAN" -> ParsedCliInput.SwitchTool(ActiveTool.Pan, "PAN: Drag canvas to pan")
            "SELECT" -> ParsedCliInput.SwitchTool(ActiveTool.Select, "SELECT: Tap entities or drag Window/Crossing box")
            "LA", "LAYER" -> ParsedCliInput.OpenLayers
            "ORTHO" -> ParsedCliInput.ToggleFlag("ORTHO")
            "GRID" -> ParsedCliInput.ToggleFlag("GRID")
            "OSNAP" -> ParsedCliInput.ToggleFlag("OSNAP")
            "POLAR" -> ParsedCliInput.ToggleFlag("POLAR")
            "SELECTALL", "AI_SELALL" -> ParsedCliInput.SelectAll
            "DESELECT" -> ParsedCliInput.DeselectAll
            "SAVE", "QSAVE" -> ParsedCliInput.SaveDrawing
            "HELP", "?" -> ParsedCliInput.Help
            else -> ParsedCliInput.Unknown(input)
        }
    }
}

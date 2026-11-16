package com.example.cad.model

import java.util.UUID

sealed class CadEntity {
    abstract val id: String
    abstract val layerName: String
    abstract val colorArgb: Int?       // null = ByLayer
    abstract val lineType: CadLineType? // null = ByLayer
    abstract val lineWeightMm: Float?  // null = ByLayer
    abstract val transparency: Float   // 0.0f = opaque, 0.9f = 90% transparent
    abstract val isSelected: Boolean

    abstract fun copyCommon(
        id: String = this.id,
        layerName: String = this.layerName,
        colorArgb: Int? = this.colorArgb,
        lineType: CadLineType? = this.lineType,
        lineWeightMm: Float? = this.lineWeightMm,
        transparency: Float = this.transparency,
        isSelected: Boolean = this.isSelected
    ): CadEntity

    data class CadLine(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val start: Vec2,
        val end: Vec2
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadPolyline(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val points: List<Vec2>,
        val isClosed: Boolean = false
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadCircle(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val center: Vec2,
        val radius: Double
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadArc(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val center: Vec2,
        val radius: Double,
        val startAngleDeg: Double,
        val endAngleDeg: Double
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadEllipse(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val center: Vec2,
        val majorAxisEndOffset: Vec2,
        val minorToMajorRatio: Double,
        val startParamRad: Double = 0.0,
        val endParamRad: Double = 2.0 * Math.PI
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadSpline(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val controlPoints: List<Vec2>,
        val degree: Int = 3,
        val isClosed: Boolean = false
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadPoint(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val position: Vec2
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadRay(
        override val id: String = newEntityId(),
        override val layerName: String = "CONSTRUCTION",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val origin: Vec2,
        val direction: Vec2
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadXLine(
        override val id: String = newEntityId(),
        override val layerName: String = "CONSTRUCTION",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val origin: Vec2,
        val direction: Vec2
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadText(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val position: Vec2,
        val text: String,
        val height: Double = 4.0,
        val rotationDeg: Double = 0.0
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadBlockReference(
        override val id: String = newEntityId(),
        override val layerName: String = "0",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val blockName: String,
        val insertionPoint: Vec2,
        val scaleX: Double = 1.0,
        val scaleY: Double = 1.0,
        val rotationDeg: Double = 0.0
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    data class CadDimension(
        override val id: String = newEntityId(),
        override val layerName: String = "DIMENSIONS",
        override val colorArgb: Int? = null,
        override val lineType: CadLineType? = null,
        override val lineWeightMm: Float? = null,
        override val transparency: Float = 0f,
        override val isSelected: Boolean = false,
        val dimType: DimensionType,
        val p1: Vec2,
        val p2: Vec2,
        val p3: Vec2? = null,
        val dimLinePoint: Vec2,
        val textOverride: String? = null,
        val associatedEntityIds: List<String> = emptyList(),
        val leaderLabel: String = ""
    ) : CadEntity() {
        override fun copyCommon(
            id: String, layerName: String, colorArgb: Int?, lineType: CadLineType?,
            lineWeightMm: Float?, transparency: Float, isSelected: Boolean
        ) = copy(
            id = id, layerName = layerName, colorArgb = colorArgb, lineType = lineType,
            lineWeightMm = lineWeightMm, transparency = transparency, isSelected = isSelected
        )
    }

    companion object {
        fun newEntityId(): String = UUID.randomUUID().toString().take(12)
    }
}

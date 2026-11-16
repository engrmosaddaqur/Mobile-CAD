package com.example.cad.engine

import com.example.cad.model.CadDocument

/**
 * Dedicated Undo/Redo Stack Manager for CAD drafting operations.
 *
 * Designed as pure Kotlin so it can be shared cleanly across Android, desktop, and web targets.
 * Uses structural sharing of immutable [CadDocument] snapshots with entity-count-aware depth limits
 * to avoid excessive memory growth on huge drawings.
 */
class UndoRedoManager(
    private val defaultMaxDepth: Int = 60
) {
    data class HistoryEntry(
        val document: CadDocument,
        val actionLabel: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class StepResult(
        val document: CadDocument,
        val actionLabel: String
    )

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size
    val nextUndoLabel: String? get() = undoStack.lastOrNull()?.actionLabel
    val nextRedoLabel: String? get() = redoStack.lastOrNull()?.actionLabel

    fun undoHistoryLabels(limit: Int = 20): List<String> =
        undoStack.asReversed().take(limit).map { it.actionLabel }

    fun redoHistoryLabels(limit: Int = 20): List<String> =
        redoStack.asReversed().take(limit).map { it.actionLabel }

    /**
     * Records a pre-mutation [beforeDocument] snapshot for [actionLabel] and clears any redo branch.
     */
    fun record(beforeDocument: CadDocument, actionLabel: String) {
        val label = actionLabel.ifBlank { "Edit Drawing" }
        undoStack.addLast(HistoryEntry(document = beforeDocument, actionLabel = label))
        val effectiveLimit = maxDepthFor(beforeDocument.entities.size)
        while (undoStack.size > effectiveLimit) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun undo(currentDocument: CadDocument): StepResult? = undoSteps(currentDocument, 1)

    fun redo(currentDocument: CadDocument): StepResult? = redoSteps(currentDocument, 1)

    /**
     * Reverts [steps] consecutive actions from the undo stack and pushes each reverted state
     * onto the redo stack so any undone sequence can be cleanly reapplied.
     */
    fun undoSteps(currentDocument: CadDocument, steps: Int): StepResult? {
        val count = steps.coerceIn(1, undoStack.size.coerceAtLeast(1))
        if (undoStack.isEmpty()) return null
        var active = currentDocument
        var lastLabel = ""
        repeat(count) {
            val prev = undoStack.removeLastOrNull() ?: return@repeat
            redoStack.addLast(HistoryEntry(document = active, actionLabel = prev.actionLabel))
            active = prev.document
            lastLabel = prev.actionLabel
        }
        return StepResult(document = active, actionLabel = lastLabel)
    }

    /**
     * Reapplies [steps] consecutive actions from the redo stack back onto the document.
     */
    fun redoSteps(currentDocument: CadDocument, steps: Int): StepResult? {
        val count = steps.coerceIn(1, redoStack.size.coerceAtLeast(1))
        if (redoStack.isEmpty()) return null
        var active = currentDocument
        var lastLabel = ""
        repeat(count) {
            val next = redoStack.removeLastOrNull() ?: return@repeat
            undoStack.addLast(HistoryEntry(document = active, actionLabel = next.actionLabel))
            active = next.document
            lastLabel = next.actionLabel
        }
        return StepResult(document = active, actionLabel = lastLabel)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun maxDepthFor(entityCount: Int): Int = when {
        entityCount >= 25_000 -> minOf(defaultMaxDepth, 15)
        entityCount >= 10_000 -> minOf(defaultMaxDepth, 30)
        else -> defaultMaxDepth
    }
}

package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.example.cad.geometry.GeometryEngine
import com.example.cad.io.CadExportEngine
import com.example.cad.io.DwgEngine
import com.example.cad.io.DwgOperationResult
import com.example.cad.io.DxfParser
import com.example.cad.io.DxfWriter
import com.example.cad.io.SampleDrawings
import com.example.cad.model.CadDocument
import com.example.cad.model.CadSourceFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class CadImportOutcome {
    data class Success(val document: CadDocument) : CadImportOutcome()
    data class Error(val title: String, val message: String) : CadImportOutcome()
}

/**
 * Process-wide LRU handoff cache so opening a recently imported, created, or edited drawing
 * skips redundant disk DXF re-parsing on Workspace entry.
 */
object CadDocumentMemoryCache {
    private val cache = object : LruCache<String, CadDocument>(4) {}

    fun put(doc: CadDocument) {
        cache.put(doc.id, doc)
    }

    fun get(id: String): CadDocument? = cache.get(id)

    fun remove(id: String) {
        cache.remove(id)
    }
}

class CadFileRepository(
    private val context: Context,
    private val dao: RecentDrawingDao
) {
    private val drawingsDir: File
        get() = File(context.filesDir, "drawings").apply { mkdirs() }

    private val backupsDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    private val thumbsDir: File
        get() = File(context.filesDir, "thumbnails").apply { mkdirs() }

    val recentDrawings: Flow<List<RecentDrawingEntity>> = dao.observeAll()

    suspend fun seedInitialSamplesIfEmpty() = withContext(Dispatchers.IO) {
        val existing = drawingsDir.listFiles()
        if (existing != null && existing.isNotEmpty()) return@withContext
        for (sample in SampleDrawings.allSampleTemplates()) {
            saveDrawingInternal(sample, clearRecovery = true)
        }
    }

    suspend fun getPendingRecovery(): RecentDrawingEntity? = withContext(Dispatchers.IO) {
        dao.getLatestPendingRecovery()
    }

    suspend fun loadDrawingById(id: String, preferRecovery: Boolean = false): CadDocument? = withContext(Dispatchers.IO) {
        if (!preferRecovery) {
            CadDocumentMemoryCache.get(id)?.let { return@withContext it }
        }
        val entry = dao.getById(id) ?: return@withContext null
        val targetFile = if (preferRecovery && entry.hasUnsavedRecovery && File(entry.autosavePath).exists()) {
            File(entry.autosavePath)
        } else {
            File(entry.filePath)
        }
        if (!targetFile.exists()) return@withContext null
        FileInputStream(targetFile).use { fis ->
            val parsed = DxfParser.parse(fis, entry.name)
            val result = parsed.copy(
                id = entry.id,
                projectGroup = entry.projectGroup,
                sourceFormat = runCatching { CadSourceFormat.valueOf(entry.format) }.getOrDefault(CadSourceFormat.DXF),
                lastModifiedMs = entry.lastModifiedMs,
                isDirty = preferRecovery && entry.hasUnsavedRecovery
            )
            CadDocumentMemoryCache.put(result)
            result
        }
    }

    suspend fun saveDrawingInternal(doc: CadDocument, clearRecovery: Boolean = true): CadDocument = withContext(Dispatchers.IO) {
        val mainFile = File(drawingsDir, "${doc.id}.dxf")
        val autosaveFile = File(drawingsDir, "autosave_${doc.id}.dxf")
        val thumbFile = File(thumbsDir, "${doc.id}.png")

        // Create backup before overwriting existing drawing
        if (mainFile.exists()) {
            val backupFile = File(backupsDir, "${doc.id}.bak.dxf")
            mainFile.copyTo(backupFile, overwrite = true)
        }

        val bounds = GeometryEngine.boundsOfAll(doc.entities, doc.blocks)
        FileOutputStream(mainFile).use { fos ->
            DxfWriter.write(doc, fos, precomputedBounds = bounds)
        }
        if (clearRecovery && autosaveFile.exists()) {
            autosaveFile.delete()
        }

        val thumbBmp = CadExportEngine.renderThumbnailBitmap(
            doc = doc,
            widthPx = 420,
            heightPx = 280,
            precomputedBounds = bounds
        )
        FileOutputStream(thumbFile).use { fos ->
            thumbBmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
        }
        thumbBmp.recycle()

        val now = System.currentTimeMillis()
        dao.upsert(
            RecentDrawingEntity(
                id = doc.id,
                name = doc.name,
                projectGroup = doc.projectGroup,
                format = doc.sourceFormat.label,
                entityCount = doc.entities.size,
                filePath = mainFile.absolutePath,
                autosavePath = autosaveFile.absolutePath,
                thumbnailPath = thumbFile.absolutePath,
                hasUnsavedRecovery = !clearRecovery,
                lastModifiedMs = now
            )
        )
        val savedDoc = doc.copy(lastModifiedMs = now, isDirty = !clearRecovery)
        CadDocumentMemoryCache.put(savedDoc)
        savedDoc
    }

    suspend fun writeAutoSave(doc: CadDocument) = withContext(Dispatchers.IO) {
        val mainFile = File(drawingsDir, "${doc.id}.dxf")
        val autosaveFile = File(drawingsDir, "autosave_${doc.id}.dxf")
        val thumbFile = File(thumbsDir, "${doc.id}.png")
        FileOutputStream(autosaveFile).use { fos ->
            DxfWriter.write(doc, fos)
        }
        val existing = dao.getById(doc.id)
        if (existing == null) {
            FileOutputStream(mainFile).use { fos -> DxfWriter.write(doc, fos) }
        }
        dao.upsert(
            RecentDrawingEntity(
                id = doc.id,
                name = doc.name,
                projectGroup = doc.projectGroup,
                format = doc.sourceFormat.label,
                entityCount = doc.entities.size,
                filePath = mainFile.absolutePath,
                autosavePath = autosaveFile.absolutePath,
                thumbnailPath = thumbFile.absolutePath,
                hasUnsavedRecovery = true,
                lastModifiedMs = System.currentTimeMillis()
            )
        )
        CadDocumentMemoryCache.put(doc)
    }

    suspend fun discardRecovery(id: String) = withContext(Dispatchers.IO) {
        val entry = dao.getById(id)
        entry?.let {
            File(it.autosavePath).delete()
            dao.setRecoveryFlag(id, false)
        }
    }

    suspend fun renameDrawing(id: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        dao.rename(id, trimmed, System.currentTimeMillis())
        CadDocumentMemoryCache.get(id)?.let { cached ->
            CadDocumentMemoryCache.put(cached.copy(name = trimmed))
        }
    }

    suspend fun deleteDrawing(id: String) = withContext(Dispatchers.IO) {
        CadDocumentMemoryCache.remove(id)
        val entry = dao.getById(id)
        entry?.let {
            File(it.filePath).delete()
            File(it.autosavePath).delete()
            File(it.thumbnailPath).delete()
        }
        dao.deleteById(id)
    }

    suspend fun importFromUri(uri: Uri): CadImportOutcome = withContext(Dispatchers.IO) {
        val displayName = resolveFileName(uri) ?: "Imported_Drawing"
        val baseName = displayName.substringBeforeLast(".").ifBlank { displayName }
        val ext = displayName.substringAfterLast(".", "").lowercase()

        return@withContext try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext CadImportOutcome.Error("Import Failed", "Could not open file stream.")

            val bytes = stream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                return@withContext CadImportOutcome.Error("Import Failed", "Selected CAD file is empty.")
            }

            // Sniff magic header bytes so Android content URIs without a .dwg extension still route to DwgEngine
            val hasDwgSignature = bytes.size >= 6 &&
                bytes[0] == 'A'.code.toByte() &&
                bytes[1] == 'C'.code.toByte() &&
                bytes[2] == '1'.code.toByte() &&
                bytes[3] == '0'.code.toByte()

            val isDwgFile = (ext == "dwg" || hasDwgSignature) && !DwgEngine.isAsciiDxfPayload(bytes)

            if (isDwgFile || ext == "dwg") {
                when (val res = DwgEngine.readDwg(ByteArrayInputStream(bytes), baseName, uri.toString())) {
                    is DwgOperationResult.Success -> {
                        val saved = saveDrawingInternal(res.document, clearRecovery = true)
                        CadImportOutcome.Success(saved)
                    }
                    is DwgOperationResult.UnsupportedBinaryDwg -> {
                        CadImportOutcome.Error(
                            title = "DWG Compatibility (${res.detectedVersion})",
                            message = res.diagnosticMessage
                        )
                    }
                }
            } else {
                val parsed = DxfParser.parse(ByteArrayInputStream(bytes), baseName, uri.toString())
                val saved = saveDrawingInternal(parsed, clearRecovery = true)
                CadImportOutcome.Success(saved)
            }
        } catch (e: Exception) {
            CadImportOutcome.Error("CAD Parse Error", e.message ?: "Malformed CAD file.")
        }
    }

    suspend fun exportToUri(doc: CadDocument, uri: Uri, format: ExportFormat): Result<String> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val out = context.contentResolver.openOutputStream(uri)
                ?: error("Unable to open destination file for writing.")
            out.use { os ->
                when (format) {
                    ExportFormat.DXF -> DxfWriter.write(doc, os)
                    ExportFormat.DWG -> {
                        val ok = DwgEngine.writeDwg(doc, os)
                        if (!ok) {
                            error("Binary DWG writing requires native LibreDWG / ODA SDK. Use DXF export for full AutoCAD/BricsCAD compatibility.")
                        }
                    }
                    ExportFormat.PDF -> CadExportEngine.exportToPdf(doc, os, whitePaper = true)
                    ExportFormat.SVG -> CadExportEngine.exportToSvg(doc, os, whiteBackground = false)
                    ExportFormat.PNG -> CadExportEngine.exportToRaster(doc, os, Bitmap.CompressFormat.PNG)
                    ExportFormat.JPG -> CadExportEngine.exportToRaster(doc, os, Bitmap.CompressFormat.JPEG)
                }
            }
            "Exported ${doc.name}.${format.extension} successfully."
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    DXF("dxf", "application/dxf", "AutoCAD DXF (AC1024)"),
    DWG("dwg", "application/acad", "DWG (Requires Native Writer)"),
    PDF("pdf", "application/pdf", "Vector PDF"),
    SVG("svg", "image/svg+xml", "Vector SVG"),
    PNG("png", "image/png", "PNG Image"),
    JPG("jpg", "image/jpeg", "JPG Image")
}

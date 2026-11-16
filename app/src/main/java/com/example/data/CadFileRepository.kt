package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class CadImportOutcome {
    data class Success(val document: CadDocument) : CadImportOutcome()
    data class Error(val title: String, val message: String) : CadImportOutcome()
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
        val entry = dao.getById(id) ?: return@withContext null
        val targetFile = if (preferRecovery && entry.hasUnsavedRecovery && File(entry.autosavePath).exists()) {
            File(entry.autosavePath)
        } else {
            File(entry.filePath)
        }
        if (!targetFile.exists()) return@withContext null
        FileInputStream(targetFile).use { fis ->
            val doc = DxfParser.parse(fis, entry.name)
            doc.copy(
                id = entry.id,
                projectGroup = entry.projectGroup,
                sourceFormat = runCatching { CadSourceFormat.valueOf(entry.format) }.getOrDefault(CadSourceFormat.DXF),
                lastModifiedMs = entry.lastModifiedMs,
                isDirty = preferRecovery && entry.hasUnsavedRecovery
            )
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

        FileOutputStream(mainFile).use { fos ->
            DxfWriter.write(doc, fos)
        }
        if (clearRecovery && autosaveFile.exists()) {
            autosaveFile.delete()
        }

        val thumbBmp = CadExportEngine.renderThumbnailBitmap(doc, widthPx = 420, heightPx = 280)
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
        doc.copy(lastModifiedMs = now, isDirty = !clearRecovery)
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
    }

    suspend fun discardRecovery(id: String) = withContext(Dispatchers.IO) {
        val entry = dao.getById(id)
        entry?.let {
            File(it.autosavePath).delete()
            dao.setRecoveryFlag(id, false)
        }
    }

    suspend fun renameDrawing(id: String, newName: String) = withContext(Dispatchers.IO) {
        dao.rename(id, newName.trim(), System.currentTimeMillis())
    }

    suspend fun deleteDrawing(id: String) = withContext(Dispatchers.IO) {
        val entry = dao.getById(id)
        entry?.let {
            File(it.filePath).delete()
            File(it.autosavePath).delete()
            File(it.thumbnailPath).delete()
        }
        dao.deleteById(id)
    }

    suspend fun importFromUri(uri: Uri): CadImportOutcome = withContext(Dispatchers.IO) {
        val displayName = resolveFileName(uri) ?: "Imported_Drawing.dxf"
        val baseName = displayName.substringBeforeLast(".")
        val ext = displayName.substringAfterLast(".", "dxf").lowercase()

        return@withContext try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext CadImportOutcome.Error("Import Failed", "Could not open file stream.")

            stream.use { input ->
                if (ext == "dwg") {
                    when (val res = DwgEngine.readDwg(input, baseName, uri.toString())) {
                        is DwgOperationResult.Success -> {
                            val saved = saveDrawingInternal(res.document, clearRecovery = true)
                            CadImportOutcome.Success(saved)
                        }
                        is DwgOperationResult.UnsupportedBinaryDwg -> {
                            CadImportOutcome.Error(
                                title = "Native DWG SDK Required (${res.detectedVersion})",
                                message = res.diagnosticMessage
                            )
                        }
                    }
                } else {
                    val parsed = DxfParser.parse(input, baseName, uri.toString())
                    val saved = saveDrawingInternal(parsed, clearRecovery = true)
                    CadImportOutcome.Success(saved)
                }
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
                            error("Native LibreDWG / ODA SDK binary is not linked for binary DWG encoding. Use DXF export for full AutoCAD/BricsCAD compatibility.")
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
    DWG("dwg", "application/acad", "DWG (Requires Native SDK)"),
    PDF("pdf", "application/pdf", "Vector PDF"),
    SVG("svg", "image/svg+xml", "Vector SVG"),
    PNG("png", "image/png", "PNG Image"),
    JPG("jpg", "image/jpeg", "JPG Image")
}

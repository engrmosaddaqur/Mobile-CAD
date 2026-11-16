package com.example.cad.io

import com.example.cad.model.CadDocument
import com.example.cad.model.CadSourceFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Native DWG SDK Bridge Interface.
 *
 * Proprietary binary DWG decoding/encoding requires a native C/C++ DWG library such as:
 * - Open Design Alliance (ODA) Drawings SDK (commercial)
 * - GNU LibreDWG compiled for Android NDK ABIs (arm64-v8a, armeabi-v7a, x86_64) via JNI (`liblibredwg_jni.so`)
 *
 * When the native shared library is bundled in `app/src/main/jniLibs/<abi>/`, this bridge
 * converts binary DWG <-> DXF streams losslessly in memory before parsing into [CadDocument].
 */
interface DwgSdkBridge {
    val sdkName: String
    fun isNativeLibraryLoaded(): Boolean
    fun convertDwgToDxf(dwgBytes: ByteArray): ByteArray
    fun convertDxfToDwg(dxfBytes: ByteArray, versionCode: String = "AC1024"): ByteArray
}

object DefaultDwgSdkBridge : DwgSdkBridge {
    override val sdkName: String = "LibreDWG / ODA Drawings SDK JNI Adapter"

    private val nativeAvailable: Boolean by lazy {
        try {
            System.loadLibrary("libredwg_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    override fun isNativeLibraryLoaded(): Boolean = nativeAvailable

    external override fun convertDwgToDxf(dwgBytes: ByteArray): ByteArray
    external override fun convertDxfToDwg(dxfBytes: ByteArray, versionCode: String): ByteArray
}

sealed class DwgOperationResult {
    data class Success(val document: CadDocument, val versionLabel: String) : DwgOperationResult()
    data class UnsupportedBinaryDwg(
        val detectedVersion: String,
        val diagnosticMessage: String
    ) : DwgOperationResult()
}

object DwgEngine {

    private val dwgVersionMap = mapOf(
        "AC1006" to "AutoCAD R10 DWG",
        "AC1009" to "AutoCAD R11/R12 DWG",
        "AC1012" to "AutoCAD R13 DWG",
        "AC1014" to "AutoCAD R14 DWG",
        "AC1015" to "AutoCAD 2000/2002 DWG",
        "AC1018" to "AutoCAD 2004/2005/2006 DWG",
        "AC1021" to "AutoCAD 2007/2008/2009 DWG",
        "AC1024" to "AutoCAD 2010/2011/2012 DWG",
        "AC1027" to "AutoCAD 2013–2017 DWG",
        "AC1032" to "AutoCAD 2018–2025 DWG"
    )

    fun inspectVersionHeader(bytes: ByteArray): String {
        if (bytes.size < 6) return "Unknown (<6 bytes)"
        val header = bytes.copyOfRange(0, 6).decodeToString()
        return dwgVersionMap[header] ?: "Unrecognized DWG header ($header)"
    }

    fun isAsciiDxfPayload(bytes: ByteArray): Boolean {
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 256)).decodeToString()
        return sample.contains("SECTION") && (sample.contains("HEADER") || sample.contains("ENTITIES") || sample.contains("0\n") || sample.contains("0\r\n"))
    }

    fun readDwg(
        inputStream: InputStream,
        drawingName: String,
        sourceUri: String? = null,
        bridge: DwgSdkBridge = DefaultDwgSdkBridge
    ): DwgOperationResult {
        val bytes = inputStream.readBytes()

        // 1. If a file saved with .dwg extension actually wraps an ASCII DXF stream (common interop), parse directly
        if (isAsciiDxfPayload(bytes)) {
            val doc = DxfParser.parse(ByteArrayInputStream(bytes), drawingName, sourceUri)
                .copy(sourceFormat = CadSourceFormat.DWG)
            return DwgOperationResult.Success(doc, "DXF-compatible DWG container")
        }

        // 2. Binary DWG detected — inspect version signature
        val versionLabel = inspectVersionHeader(bytes)

        // 3. Delegate to native LibreDWG / ODA SDK if JNI binary is linked
        if (bridge.isNativeLibraryLoaded()) {
            return try {
                val dxfBytes = bridge.convertDwgToDxf(bytes)
                val doc = DxfParser.parse(ByteArrayInputStream(dxfBytes), drawingName, sourceUri)
                    .copy(sourceFormat = CadSourceFormat.DWG)
                DwgOperationResult.Success(doc, versionLabel)
            } catch (e: Throwable) {
                DwgOperationResult.UnsupportedBinaryDwg(
                    detectedVersion = versionLabel,
                    diagnosticMessage = "Native DWG SDK failed to decode $versionLabel: ${e.message}"
                )
            }
        }

        // 4. Honest reporting: do NOT fake binary DWG editing
        return DwgOperationResult.UnsupportedBinaryDwg(
            detectedVersion = versionLabel,
            diagnosticMessage = "Detected binary $versionLabel (${bytes.size / 1024} KB). " +
                "Full binary DWG decoding requires linking the native ${bridge.sdkName} (`liblibredwg_jni.so` or ODA Drawings SDK). " +
                "Export your drawing to ASCII DXF (AC1015–AC1032) for lossless offline mobile editing, or bundle the native DWG NDK library."
        )
    }

    fun writeDwg(
        doc: CadDocument,
        outputStream: OutputStream,
        bridge: DwgSdkBridge = DefaultDwgSdkBridge
    ): Boolean {
        val dxfOut = ByteArrayOutputStream()
        DxfWriter.write(doc, dxfOut)
        val dxfBytes = dxfOut.toByteArray()

        if (bridge.isNativeLibraryLoaded()) {
            val dwgBytes = bridge.convertDxfToDwg(dxfBytes, "AC1024")
            outputStream.write(dwgBytes)
            outputStream.flush()
            return true
        }
        return false
    }
}

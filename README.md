# MOBILE CAD
**Tagline:** *Professional CAD Design in Your Pocket*

MOBILE CAD is a modular, offline-capable 2D CAD drafting and engineering application for Android smartphones and tablets, built with Kotlin, Jetpack Compose, and a custom hardware-accelerated 2D vector CAD rendering & geometry engine.

## Architecture Overview
1. **CAD Data & File Engine (`com.example.cad.model`, `com.example.cad.io`)**:
   - `CadDocument`, `CadEntity` (`CadLine`, `CadPolyline`, `CadCircle`, `CadArc`, `CadEllipse`, `CadSpline`, `CadPoint`, `CadRay`, `CadXLine`, `CadText`, `CadBlockReference`, `CadDimension`), `CadLayer`, `CadLayout`, `AciColors`.
   - `DxfParser` & `DxfWriter`: Streaming ASCII DXF (AC1024) reader/writer preserving `$INSUNITS`, `LAYER` tables (color, line type, line weight, freeze/lock/on flags), `BLOCKS` (`INSERT`), and all 2D entities, with diagnostic warnings for unsupported entity types.
   - `DwgEngine`: Inspects binary DWG version headers (`AC1006`–`AC1032`), parses DXF-wrapped DWG payloads directly, and provides the `DwgSdkBridge` JNI adapter for linking native **LibreDWG** (`liblibredwg_jni.so`) or **ODA Drawings SDK** libraries in `app/src/main/jniLibs/<abi>/`.
   - `CadExportEngine`: Vector PDF (`android.graphics.pdf.PdfDocument`), layered W3C SVG 1.1, and high-resolution PNG/JPG raster export.
2. **Geometry & Precision Snap Engine (`com.example.cad.geometry`)**:
   - `GeometryEngine` & `GeometryOperations`: Exact 2D intersections, hit testing, Window/Crossing selection, Move, Copy, Rotate, Mirror, Offset, Trim, Extend, Stretch, Fillet, Chamfer, Scale, Rectangular/Polar Array, Explode, Join, Break, Grip editing, and Associative Dimension updates.
   - `SnapEngine`: Endpoint, Midpoint, Center, Intersection, Perpendicular, Tangent, Nearest, Quadrant, Extension, Grid Snap, Ortho Mode, and Polar Tracking.
3. **CAD Rendering Engine (`com.example.cad.render`)**:
   - `ViewportState` & `CadCanvasRenderer`: Frustum-culled Compose `Canvas` rendering with anchored pinch-zoom, mouse wheel zoom, middle-mouse pan, adaptive grid, UCS triad, Paper Space title blocks, grips, and live tool previews.
4. **Workspace Engines & UI (`com.example.cad.engine`, `com.example.ui`)**:
   - `CadWorkspaceViewModel`, `CadToolExecutor`, `CommandProcessor` (AutoCAD-inspired CLI), Room-backed `CadFileRepository` with auto-save crash recovery and backup-before-overwrite, and `UserPreferencesRepository` (DataStore).

## Building & Testing
- Build Debug APK: `gradle :app:assembleDebug`
- Run Unit & Robolectric Tests: `gradle :app:testDebugUnitTest`
- Adding Binary DWG Native SDK: Place compiled ABI libraries (`liblibredwg_jni.so` or ODA JNI wrapper implementing `DefaultDwgSdkBridge.convertDwgToDxf` / `convertDxfToDwg`) under `app/src/main/jniLibs/arm64-v8a/` and `app/src/main/jniLibs/x86_64/`.

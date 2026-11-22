# FIKRADO Studio — Technical Architecture V1.1

**Repository:** `nuurciye/FIKRADO-STUDIO-`  
**Base revision audited:** `5744861a1c934c4e1a1999b251f33d152fa850ac`  
**Status:** Technical design; implementation not started  
**Decision:** Keep and extend the existing editor. Do not rewrite the FFmpeg conversion pipeline.

---

## 1. Repository facts this design relies on

The following paths and responsibilities were inspected in the repository:

| Existing path | Verified responsibility | V1.1 decision |
|---|---|---|
| `:core/.../engine/Ports.kt` | Platform-facing interfaces used by conversion logic | Preserve the port pattern; add production contracts separately |
| `:core/.../engine/ConversionEngine.kt` | FFmpeg-specific conversion workflow | Keep as the FFmpeg implementation; do not generalize it into every engine |
| `:core/.../engine/ConversionJob.kt` | FFmpeg job composed of ConversionRequest, MediaSources and CommandMode | Keep for existing exports; introduce a distinct production render request |
| `:core/.../timeline/Timeline.kt` | Immutable clip timeline and editing operations | Preserve and adapt through a mapping layer where needed |
| `:core/.../project/ModelJson.kt` | Explicit JSON serialization for core editor models | Extend only with tests; do not silently change old serialized formats |
| `:app/.../projects/ProjectStore.kt` | Android private-storage project persistence and media-copy management | Keep as legacy/editor persistence during migration |
| `:app/.../di/AppContainer.kt` | Hand-written Android dependency wiring | Register Android implementations here; keep core free of Android dependencies |

Current Gradle modules are `:app`, `:media`, and `:core`. Existing dependency direction is:

```
:app -> :media -> :core
```

Do not introduce a reverse dependency from `:core` to `:media` or `:app`.

The existing `ConversionJob` validates that request sources, overlay sources, sound sources, subtitle source and cover source agree. The new production request must follow the same fail-fast validation principle.

---

## 2. V1.1 architectural decision

Add a **production domain layer** beside the current editor/conversion domain. It coordinates jobs but does not replace the existing editor.

Initial package placement (proposed):

```
core/src/main/kotlin/com/cuscus/ffmpeggui/core/
  production/
    model/
      ProductionProject.kt
      ProductionProfile.kt
      ProductionAsset.kt
      ProductionScene.kt
      ProductionLayer.kt
      ProductionTime.kt
    validation/
      ProjectValidator.kt
      RenderPlanValidator.kt
    render/
      RenderRequest.kt
      RenderPlan.kt
      RenderResult.kt
      RenderJob.kt
      RenderJobState.kt
      EngineAdapter.kt
      EngineCapabilities.kt
      EngineRegistry.kt
      RenderPlanner.kt
      RenderOrchestrator.kt
    workflow/
      WorkflowTemplate.kt
      WorkflowRegistry.kt
    migration/
      LegacyProjectImporter.kt

media/src/main/java/com/cuscus/ffmpeggui/media/
  production/
    FfmpegEngineAdapter.kt
    LocalAssetResolver.kt

app/src/main/java/com/cuscus/ffmpeggui/
  production/
    ProductionViewModel.kt
    render/
      AndroidRenderQueue.kt
    di/
      ProductionBindings.kt
```

These are package/file proposals, not a mandate to create a large number of files immediately. Start with the smallest compiling vertical slice. Do not add Gradle modules in V1.1: the current three-module split is sufficient until a real dependency or build-isolation need proves otherwise.

---

## 3. Domain model contracts

All production-domain models belong in `:core`. They must be plain Kotlin/JVM types and must not import Android, Compose, FFmpegKit, filesystem APIs, network clients or engine SDKs.

### 3.1 IDs and time

Use stable string identifiers at persistence boundaries. Use milliseconds as the canonical timeline unit in V1.1 to align with the existing timeline's `Long` millisecond values.

```kotlin
@JvmInline
value class ProjectId(val value: String)

@JvmInline
value class SceneId(val value: String)

@JvmInline
value class LayerId(val value: String)

@JvmInline
value class AssetId(val value: String)

@JvmInline
value class RenderJobId(val value: String)

@JvmInline
value class EngineId(val value: String)
```

IDs must be generated at the application boundary (UUID is acceptable); model constructors should not generate random IDs implicitly. Validate non-blank IDs when loading untrusted project data.

### 3.2 Project and profile

```kotlin
data class ProductionProject(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: ProjectId,
    val name: String,
    val profile: ProductionProfile,
    val settings: ProductionSettings,
    val scenes: List<ProductionScene>,
    val assets: List<ProductionAsset>,
    val metadata: ProjectMetadata = ProjectMetadata(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class ProductionProfile {
    DOCUMENTARY,
    ADVERTISING,
    SOCIAL_MEDIA,
    THREE_D,
    CORPORATE,
    EDUCATION,
    REAL_ESTATE,
    E_COMMERCE,
    GAMING,
    EVENTS,
    NEWS,
    CUSTOM,
}

data class ProductionSettings(
    val width: Int,
    val height: Int,
    val frameRateNumerator: Int,
    val frameRateDenominator: Int = 1,
    val backgroundColorArgb: Long? = null,
)

data class ProjectMetadata(
    val createdAtEpochMs: Long? = null,
    val modifiedAtEpochMs: Long? = null,
    val description: String? = null,
)
```

Use rational frame rates (numerator/denominator) rather than assuming every frame rate is an integer. Validate positive dimensions and frame-rate terms, and impose documented upper bounds before rendering.

### 3.3 Asset references

```kotlin
enum class AssetKind {
    VIDEO, IMAGE, AUDIO, FONT, MODEL_3D, TEXTURE, VECTOR, OTHER
}

data class ProductionAsset(
    val id: AssetId,
    val kind: AssetKind,
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val checksumSha256: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
```

The URI is an opaque reference, not a guaranteed local path. Resolution, permission handling, copying and staging belong to platform/worker adapters. Do not store absolute temporary cache paths as durable project references. Avoid embedding media bytes in JSON.

### 3.4 Scene and layers

```kotlin
data class ProductionScene(
    val id: SceneId,
    val name: String,
    val durationMs: Long,
    val layers: List<ProductionLayer>,
    val backgroundColorArgb: Long? = null,
)

data class ProductionLayer(
    val id: LayerId,
    val kind: LayerKind,
    val startMs: Long,
    val durationMs: Long,
    val assetId: AssetId? = null,
    val transform: LayerTransform = LayerTransform(),
    val opacity: Float = 1f,
    val zIndex: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val engineExtensions: Map<String, Map<String, String>> = emptyMap(),
)

enum class LayerKind {
    VIDEO, IMAGE, AUDIO, TEXT, SHAPE, THREE_D, COMPOSITION
}

data class LayerTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
)
```

This is the minimum shared contract, not a claim that all engines support every property. Engine-specific extension keys must be namespaced by engine ID and versioned. Do not serialize arbitrary executable code in project properties.

V1.1 intentionally does not define a universal keyframe/effect schema. Reuse or map existing editor models where semantics match; introduce a shared animation contract only after documenting interpolation, timebase, easing and engine compatibility.

### 3.5 Validation rules

A `ProjectValidator` in `:core` must report structured issues, not throw for ordinary invalid user data.

Minimum checks:

- Supported schema version.
- Non-blank project, scene, layer and asset IDs.
- No duplicate IDs within their respective collections.
- Positive canvas dimensions and valid frame-rate rational.
- Non-negative scene/layer start and duration.
- Layer interval does not exceed its scene unless explicitly supported later.
- Referenced asset IDs exist.
- Opacity and transform values are finite; opacity is within 0..1.
- Required properties exist for each layer kind.
- Project and scene size/count limits are enforced before allocating large resources.

Use an explicit validation result such as `ValidationResult(valid, issues)`; each issue should include a stable code, location/path and human-readable message.

---

## 4. Engine adapter contract

The adapter describes one execution backend. It does not own the editor UI or project persistence.

```kotlin
interface EngineAdapter {
    val id: EngineId

    fun capabilities(): EngineCapabilities

    suspend fun validate(request: EngineRenderRequest): List<RenderIssue>

    suspend fun render(
        request: EngineRenderRequest,
        onProgress: (EngineProgress) -> Unit,
    ): EngineRenderResult
}

data class EngineCapabilities(
    val supportedLayerKinds: Set<LayerKind>,
    val supportedCodecs: Set<String> = emptySet(),
    val supportsAlpha: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsCancellation: Boolean = true,
)

data class EngineProgress(
    val fraction: Float?,
    val message: String? = null,
)

data class EngineRenderRequest(
    val jobId: RenderJobId,
    val project: ProductionProject,
    val sceneIds: List<SceneId>,
    val output: RenderOutputSpec,
    val workingDirectoryUri: String? = null,
)

data class RenderOutputSpec(
    val width: Int,
    val height: Int,
    val frameRateNumerator: Int,
    val frameRateDenominator: Int,
    val container: String,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val outputUri: String,
)

data class EngineRenderResult(
    val success: Boolean,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
```

The contract is intentionally coroutine-based. Cancellation should propagate through coroutine cancellation where the backend supports it. Adapters must document whether cancellation is immediate, best-effort or unsupported. Never swallow `CancellationException`.

Before implementation, refine the result type into a sealed result if required by the project's Kotlin conventions. Progress fractions must be null when unknown and otherwise finite within 0..1.

### Initial adapters

| Adapter ID | Engine | First responsibility |
|---|---|---|
| `ffmpeg` | Existing FFmpeg runtime | Existing supported media assembly/export |
| `remotion` | Remotion worker | React-based titles, charts and motion compositions |
| `blender` | Blender worker | 3D scene rendering |
| `hyperframes` | HyperFrames worker | HTML/CSS/JS composition rendering |
| `threejs` | Three.js worker | Web 3D scene rendering |

Do not create placeholder adapters that claim to render when they only return a fake success. An unavailable engine must be reported as unavailable.

---

## 5. Render planning and orchestration

Separate **planning** from **execution**.

- `RenderPlanner`: inspects the project, requested output and adapter capabilities; produces a deterministic plan or structured incompatibility issues.
- `RenderOrchestrator`: executes the plan, forwards progress, handles cancellation and records outcomes.
- `EngineRegistry`: provides registered adapters by ID.
- `RenderQueue`: application/worker concern for scheduling and persistence; it must not be a global mutable singleton in `:core`.

A plan should contain ordered render steps, dependencies between steps, selected engine IDs and expected outputs. V1.1 should initially support a simple linear plan. A DAG for parallel scene rendering can be added after the first working adapter.

The planner must reject unsupported layer types or effects rather than silently dropping them. If an engine supports only a subset of a scene, either split the work explicitly into compatible steps or return a clear incompatibility report.

### Hybrid example

1. Blender renders a 3D product scene to an intermediate file.
2. Remotion renders typography and animated graphics.
3. FFmpeg combines compatible video/audio outputs and encodes the final deliverable.

This is an example execution plan, not a feature already implemented.

---

## 6. Render job lifecycle

Keep job state as a serializable domain model.

```kotlin
enum class RenderJobState {
    CREATED,
    VALIDATING,
    QUEUED,
    RUNNING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class RenderJob(
    val id: RenderJobId,
    val projectId: ProjectId,
    val state: RenderJobState,
    val progress: Float? = null,
    val message: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val attempt: Int = 0,
    val outputUri: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
```

Allowed state transitions must be centralized and tested. Terminal states are COMPLETED, FAILED and CANCELLED. A retry creates a new attempt or a new job according to the chosen persistence policy; it must not silently reset a completed/failed record.

Queue responsibilities in the Android app or desktop/cloud worker:

- Bounded concurrency.
- Durable job state if jobs must survive process death.
- Cancellation propagation.
- Retry policy for explicitly retryable failures only.
- Output cleanup without deleting user source assets.
- Clear distinction between queued, running and worker-offline jobs.

Do not use an unbounded in-memory queue as the final production implementation.

---

## 7. Legacy project compatibility and migration

The existing Android `ProjectStore` persists `SavedProject` state under app-private storage and manages copied media and URI permissions. Existing `ModelJson` explicitly serializes editor models. These are important compatibility boundaries.

Migration strategy:

1. Keep old project JSON readable and writable.
2. Add a `LegacyProjectImporter` that maps supported legacy editor state into `ProductionProject`.
3. Keep unsupported or editor-specific state in a compatibility payload or continue to reference the legacy editor model; do not discard it.
4. Make import non-destructive: never rewrite the source project during import.
5. Add fixture-based tests using real legacy project JSON from the repository's test fixtures or a sanitized fixture created from the existing serializer.
6. Only introduce a new universal project save format after round-trip, migration and downgrade behavior are documented.

The universal model must not assume the current `ConversionSettings` object is itself a universal production project.

---

## 8. Dependency injection and platform boundaries

- Core models, validation, planning and orchestration interfaces stay in `:core`.
- Android implementations and Android URI/file handling stay in `:media` or `:app`, according to the existing boundary.
- `AppContainer` remains the hand-written composition root for Android implementations.
- Remote worker HTTP/WebSocket clients must not be imported into `:core`.
- External engine process launching belongs to a worker adapter, not a Compose screen or core model.
- Secrets and worker credentials must never be stored in project JSON.

For the first vertical slice, register only the existing FFmpeg adapter if it can faithfully map the new request to existing `ConversionRequest` and `ConversionJob`. If a safe mapping is not yet possible, keep FFmpeg exports on the existing path and test the new orchestration with a deterministic fake adapter in JVM tests. Do not force a lossy mapping merely to make the architecture look unified.

---

## 9. Testing and acceptance gates

### Gate A — model foundation

- Production project model compiles on JVM.
- JSON round-trip preserves supported fields.
- Invalid IDs, durations, dimensions, frame rates and references are rejected.
- No Android imports in production core.

### Gate B — planner

- Selects only adapters whose capabilities cover the requested work.
- Rejects unsupported layers/effects with stable issue codes.
- Produces deterministic plans for the same input.
- Does not mutate the project.

### Gate C — orchestrator

- Progress is forwarded and normalized.
- Success and failure outcomes are represented correctly.
- Coroutine cancellation propagates.
- Failed/cancelled jobs do not report success or leave partial output untracked.

### Gate D — legacy regression

- Existing `:core:test` remains green.
- Existing timeline editing tests remain unchanged and green.
- Existing project serialization fixtures still load.
- Existing FFmpeg preview/export behavior is not modified by this architecture-only phase.

No build or test result is claimed by this document. The repository audit did not execute the build.

---

## 10. Implementation sequence

1. Add production model types and validator in `:core`.
2. Add serialization and fixture tests without changing legacy serialization.
3. Add engine capability and render-plan contracts.
4. Add a fake adapter and JVM tests for planning/orchestration.
5. Add job-state transition tests and a bounded queue design.
6. Implement the first real adapter only after its execution environment is available.
7. Add a Remotion worker prototype as the first external engine, then validate output handoff into FFmpeg.
8. Add Blender worker integration after the worker protocol and asset transfer contract are proven.
9. Add HyperFrames and Three.js when distinct workflows justify them.
10. Add workflow templates as configuration over the shared project model, not separate editors.

---

## 11. Explicit non-goals

V1.1 does not:

- Rewrite the existing FFmpeg editor or timeline.
- Run Blender, Remotion or HyperFrames inside the Android app.
- Add a cloud rendering subscription requirement.
- Implement every production workflow.
- Promise that every layer can be rendered by every engine.
- Replace the existing project store or JSON format.
- Introduce a new Gradle module before a demonstrated need.
- Claim any engine integration, build or test has already succeeded.

**Architecture decision:** FIKRADO Studio is a shared production domain and orchestration layer around the existing Android editor, with capability-driven adapters and external workers for heavyweight engines. The current FFmpeg conversion engine remains intact and continues to serve existing exports.

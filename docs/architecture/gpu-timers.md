# GPU phase timing

`GpuTimers` measures seven sequential intervals with OpenGL 3.3
`GL_TIME_ELAPSED`: shadow, opaque geometry (including sky), entities, water,
particles/effects, postprocessing and HUD. Each phase owns three query objects.
`next(phase)` ends the current interval before beginning another. Explicit nesting
and measuring the same phase twice in one frame are rejected.

At `beginFrame`, the oldest pending frame is checked with
`GL_QUERY_RESULT_AVAILABLE`. Results are read only when every used phase in that
frame is ready; the published sum therefore never mixes different frames. Missing
phases contribute zero. When all three slots are occupied, measurement of the new
frame is skipped. Pending queries are not overwritten, waited on or forced to
finish. The regular timing path allocates no objects per frame.

`FrameProfiler.captureGpu` copies the latest completed sample without issuing GL
calls. F3 and stress reports label GPU samples with their source frame number:
they arrive after the current CPU frame. CPU spikes and the latest GPU sample do
not necessarily describe the same frame. Stress reports count each observed GPU
sample once and provide total percentiles and phase averages.

`GpuTimersRingTests` uses a fake backend that fails on a result read before
availability, pending-query reuse, nesting and double deletion. The real GL smoke
adds an independent pair of timestamp queries around all seven measured intervals.
Only validation code uses `glFinish` to retrieve that independent baseline.

For the production shader workload, run from the repository root after compiling:

```powershell
java '-Dmineclone.gpuAudit=true' '-Dmineclone.benchQuick=true' `
  '-Dmineclone.benchFrames=40' '-Dmineclone.benchWarmup=12' `
  -cp 'out-test;libs/*' tools/BenchShaders.java
```

The audit fails if the summed phase time differs by more than 10% from the enclosing
GPU timestamps. Quick mode selects the 1280x720, Fancy 1024 PCF3 lazy-shadow case;
omit it to audit every existing benchmark configuration. The first full measure
is discarded as before to let the driver prepare its pipelines.

Verified on NVIDIA OpenGL 4.4.0 / driver 610.88 on 2026-09-24:

| Workload | Phase sum | Enclosing GPU frame | Difference | Timer CPU cost/frame |
| --- | ---: | ---: | ---: | ---: |
| GL smoke, seven intervals | 0.409 ms | 0.415 ms | 1.28% | 0.0071 ms |
| BenchShaders, 169 chunks / 319102 triangles, final measure | 0.577 ms | 0.581 ms | 0.52% | 0.0228 ms |

The shader benchmark's discarded first measure cost 0.0513 ms/frame in timer calls;
the final warmed measure meets the 0.05 ms target. These are local measurements,
not a performance guarantee for other drivers or hardware.

API references: [Khronos query objects](https://wikis.khronos.org/opengl/Query_Object)
and [LWJGL GL33 query bindings](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL33.html).

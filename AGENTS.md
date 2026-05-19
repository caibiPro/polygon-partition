# AGENTS.md

This file provides project-level guidance for Codex when working in this repository.

## Communication

- Always reply in natural, professional Simplified Chinese.
- Keep technical terms in English when they are clearer or match source-code names.
- Do not use emojis.

## Commands

```bash
# Full verification
./mvnw clean test

# Run one test class
./mvnw test -Dtest=AdjacencyGraphBuilderTest

# Run the manual real-data smoke test. The test is @Disabled by default.
./mvnw test -Dtest=RealDataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Current baseline:

```text
Tests run: 31, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

The skipped test is the manual real-data smoke test.

Real-data Shapefile path used by the smoke test:

```text
~/Documents/files/shapefiles/四川测试数据/地块数据.shp
```

## Problem Domain

The repository implements a Java/Maven pipeline for farmland polygon partitioning.

Business goal: given farmland polygon Shapefiles, group plots by village (`CUNDM`) and produce balanced work packages for field operations.

Target constraints:

- Each package contains at most `maxGroupSize` plots.
- Plots inside one package should be spatially connected.
- Package sizes inside the same village should be balanced enough to avoid extreme splits such as one huge package and one tiny package.

## Architecture

Core pipeline:

```text
Shapefile (.shp)
  -> ShapefileReader.readFeatures()          -> List<SimpleFeature>
  -> SichuanPlotMapper.map()                 -> List<Plot>
  -> GeometryValidator.validate()            -> List<QCReport>
  -> group Plot by villageCode
  -> AdjacencyGraphBuilder.build()           -> List<WeightedEdge>
  -> connected components per village graph
  -> MaxSpanningTree.compute() per component -> List<WeightedEdge>
  -> tree partitioning strategy              -> List<List<Plot>>
```

Package responsibilities:

| Package | Responsibility |
|---|---|
| `com.mingqing.partition.domain` | Internal domain objects, especially `Plot`. |
| `com.mingqing.partition.io` | External file input/output, including Shapefile reading. |
| `com.mingqing.partition.mapper` | Anti-corruption layer from external schemas to domain objects. |
| `com.mingqing.partition.geometry` | Geometry validation, STRtree indexing, and geometry operations. |
| `com.mingqing.partition.graph` | Graph primitives and graph algorithms. |
| `com.mingqing.partition.cut` | Partitioning algorithms and orchestration. |
| `com.mingqing.study` | Learning/demo code only; production code must not depend on it. |

## Design Constraints

- Keep GeoTools `SimpleFeature` out of `geometry`, `graph`, and `cut`; map to `Plot` first.
- Treat `mapper` as the schema boundary. Field names such as `OBJECTID`, `WYBH`, and `CUNDM` belong there, not in core algorithms.
- Do not silently repair invalid geometry inside graph building. Report data quality through `GeometryValidator` and `QCReport`.
- `GeometryOps.sharedBoundaryLength` should compute shared boundary length; topology repair decisions belong to callers.
- `AdjacencyGraphBuilder` should use identity-based geometry lookup when mapping candidate geometries back to indexes. JTS `Geometry.equals()` is geometric equality, not object identity.
- `MaxSpanningTree.compute(n, edges)` assumes a connected graph. Real village graphs must be split into connected components before MST computation.
- Keep `PartitionCutter` as the weak-edge baseline unless intentionally changing baseline behavior.
- Implement balanced recursive tree cutting as a separate algorithm before wiring it into village-level orchestration.

## Algorithm Direction

The intended production approach is a transparent, testable approximation:

```text
group Plot by village
  -> build adjacency graph using shared boundary length
  -> split graph into connected components
  -> build Maximum Spanning Tree per component
  -> recursively cut each tree by balanced subtree size
  -> stop when every group size <= maxGroupSize
  -> map node indexes back to Plot groups
```

Weak-edge cutting is useful as a baseline, but it does not satisfy the balanced package-size objective by itself.

When adding partitioning behavior:

- Start with pure graph/tree tests that do not involve Geometry.
- Add integration tests after the tree behavior is stable.
- Keep real-data smoke tests manual unless they become deterministic and cheap enough for normal test runs.

## Codex Working Rules

- Prefer small, focused edits that preserve the existing Maven/JUnit/AssertJ setup.
- Use `rg` for code search.
- Use `apply_patch` for manual file edits.
- Run `./mvnw clean test` before claiming implementation work is complete.
- Do not commit local state files, IDE workspace files, build outputs, or handoff scratch files.
- Keep cross-session guidance stable: use feature names, package boundaries, and algorithm constraints instead of temporary task numbers or conversation-local labels.

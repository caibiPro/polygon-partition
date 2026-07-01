# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# 运行所有测试（预期：BUILD SUCCESS，RealDataSmokeTest 默认 @Disabled）
./mvnw clean test

# 运行单个测试类
./mvnw test -Dtest=RecursiveSizeAlgorithmTest

# 运行手动 smoke test（需真实 Shapefile，默认 @Disabled）
./mvnw test -Dtest=RealDataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

真实数据路径：`~/Documents/files/shapefiles/四川测试数据/地块数据.shp`

## Architecture

### Pipeline 流程

```
Shapefile (.shp)
  └─ ShapefileReader.readFeatures()           → SimpleFeature（流式读取）
       └─ SichuanPlotMapper.map()             → Plot（隔离 GeoTools schema）
            ├─ GeometryValidator.validate()   → QCReport（QC，不修复）
            └─ VillagePartitioner.partition()
                 ├─ AdjacencyGraphBuilder     → 全村邻接图（STRtree + sharedBoundaryLength）
                 ├─ UnionFind                 → 连通分量
                 │    ├─ 分量 ≤ maxGroupSize → 暂存，进入合并阶段
                 │    └─ 分量 > maxGroupSize → MST + RecursivePeelingAlgorithm
                 ├─ MortonOrder.sort()        → 小分量按 Morton 曲线排序
                 ├─ SpatialMerger.merge()     → 装箱合并成任务包
                 └─ 局部编号 → Plot 映射      → List<List<Plot>>
```

### 包职责

| 包 | 职责 |
|---|---|
| `partition.domain` | `Plot` record — 唯一域对象，贯穿所有业务逻辑 |
| `partition.io` | `ShapefileReader` / `ShapefileWriter` — GeoTools 文件读写 |
| `partition.mapper` | `PlotMapper` 接口 + `SichuanPlotMapper` — 隔离 GeoTools schema |
| `partition.geometry` | `GeometryValidator` / `QCReport` / `NeighborIndex` (STRtree) / `GeometryOps` |
| `partition.graph` | `AdjacencyGraphBuilder` / `MaxSpanningTree` (Kruskal) / `UnionFind` / `WeightedEdge` / `Subgraph` |
| `partition.cut` | 策略接口：`TreeCutStrategy` / `TargetedPeelingStrategy` / `BalancedCutStrategy` |
| `partition.cut.algorithm` | `TreePartitionAlgorithm` / `RecursivePeelingAlgorithm` / `GlobalWeakestEdgeAlgorithm` |
| `partition.cut.model` | `PartitionContext` / `TreeCutDecision` |
| `partition.merge` | `MortonOrder`（排序）/ `MortonMerger` / `LocalBestFitMortonMerger` / `SortSweepMerger` / `SpatialMerger` 接口 |
| `partition.partitioner` | `VillagePartitioner` — 村级编排，串联构图→切分→合并→映射 |
| `study` | 练习代码，不被 production 代码依赖 |

### 合并策略

小连通分量（实测平均 ~23 图斑/分量）需合并成任务包。合并分两步——**排序负责紧凑度，装箱负责均衡**：

- **MortonOrder**：把簇的二维坐标量化后做位交错编码（Morton/Z-order），按码排序使"二维邻近"近似转成"一维相邻"。多个 merger 共用同一套排序。
- **MortonMerger**：balanced-target 装箱——沿排序顺序推进，达到软目标 target 即封箱。online 装箱，O(n)。
- **LocalBestFitMortonMerger(w)**：窗口化 local best-fit 装箱——封箱前向前看 window 个位置，把能塞进剩余空位的小簇就近捞进来填缝。消除大簇"路障"导致的孤儿包。`w=64` 是实测性价比最优：std 比 Morton 降低 48%，包数减少 16%，紧凑度代价仅 30%。
- **SortSweepMerger**：按 y 坐标排序装箱——紧凑度最差，作为基准对照。

### 关键设计约束

- `SimpleFeature`（GeoTools）不得进入 `geometry` / `graph` / `cut` / `merge` 包，必须先 map 成 `Plot`
- `GeometryOps.sharedBoundaryLength` 不做 `buffer(0)` 修复，TopologyException 由调用方处理（SRP）
- `AdjacencyGraphBuilder` 用 `IdentityHashMap<Geometry, Integer>` 做索引（JTS `Geometry.equals()` 是几何相等，不是引用相等）
- `MaxSpanningTree.compute(n, edges)` 要求图连通；连通分量拆分由 `VillagePartitioner` 负责，不在 MST 层处理
- `TreePartitionAlgorithm` 只接受单个连通分量的 MST（局部编号 0..n-1），不感知多分量场景
- 分组结果是集合划分：每个 Plot 出现且仅出现一次，地理边界不重合由此自动保证

### 当前进度

**已完成：**

- **Task A（QC 链路）：** 读取 → 映射 → 校验。smoke test 验证 65,387 图斑，发现 8 个 Ring Self-intersection（江阳区 8 村）。
- **Task B（切分算法层）：** `RecursivePeelingAlgorithm` + `TargetedPeelingStrategy`（目标尺寸剥离）+ `GlobalWeakestEdgeAlgorithm`（切 k-1 条最弱边）。策略可扩展：实现 `TreeCutStrategy` 即可插入新切点逻辑。
- **Task B（集成层）：** `VillagePartitioner` — 连通分量拆分 → MST 切分 → 小分量 Morton 排序 + 装箱合并。三种合并策略可用。
- **Task B（输出）：** `ShapefileWriter.writePerPackage()` — 逐包写出 Shapefile（`pkg_000.shp`...），回读校验不丢不重。

**真实数据验证（65,387 图斑，maxGroupSize=500）：**

| 策略 | 包数 | std | min | 紧凑度 |
|------|------|-----|-----|--------|
| LocalBestFit(w=64) | 135 | 60.2 | 50 | 0.003579 |
| Morton (balanced) | 160 | 116.0 | 2 | 0.002755 |
| SortSweep | 160 | 107.4 | 9 | 0.005061 |

**不变量：** 每包大小 ≤ maxGroupSize，每个 Plot 出现且仅一次（集合划分）。

### 相关文档

- `doc/problem-and-architecture.md` — 深度教学文档：问题建模推导、5 种方案对比、MST 选型理由、改进/升级方向

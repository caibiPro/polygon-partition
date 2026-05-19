# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# 运行所有测试（基准：31 passed, 1 skipped, BUILD SUCCESS）
./mvnw clean test

# 运行单个测试类
./mvnw test -Dtest=AdjacencyGraphBuilderTest

# 运行手动 smoke test（需真实 Shapefile，默认 @Disabled）
./mvnw test -Dtest=RealDataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

真实数据路径：`~/Documents/files/shapefiles/四川测试数据/地块数据.shp`

## Architecture

### Pipeline 流程

```
Shapefile (.shp)
  └─ ShapefileReader.readFeatures()        → List<SimpleFeature>
       └─ SichuanPlotMapper.map()          → List<Plot>          ← 反腐层边界
            ├─ GeometryValidator.validate() → List<QCReport>      (QC，不修复)
            └─ AdjacencyGraphBuilder.build()→ List<WeightedEdge>
                 └─ MaxSpanningTree.compute()→ List<WeightedEdge> (per-component)
                      └─ PartitionCutter.cut() → List<List<Integer>>
```

### 包职责

| 包 | 职责 |
|---|---|
| `partition.domain` | `Plot` record — 唯一域对象，贯穿所有业务逻辑 |
| `partition.io` | `ShapefileReader` — GeoTools 文件读取，返回 `SimpleFeature` |
| `partition.mapper` | `PlotMapper` 接口 + `SichuanPlotMapper` — 隔离 GeoTools schema |
| `partition.geometry` | `GeometryValidator` / `QCReport` / `NeighborIndex` (STRtree) / `GeometryOps` |
| `partition.graph` | `AdjacencyGraphBuilder` / `MaxSpanningTree` (Kruskal) / `UnionFind` / `WeightedEdge` |
| `partition.cut` | `PartitionCutter` — 切掉 k-1 条最弱 MST 边（基线策略，不保证均衡） |
| `partition.pipeline` | 集成测试 + 手动 smoke test |
| `study` | 练习代码，不被 production 代码依赖 |

### 关键设计约束

- `SimpleFeature`（GeoTools）不得进入 `geometry` / `graph` / `cut` 包，必须先 map 成 `Plot`
- `GeometryOps.sharedBoundaryLength` 不做 `buffer(0)` 修复，TopologyException 由调用方处理（SRP）
- `AdjacencyGraphBuilder` 用 `IdentityHashMap<Geometry, Integer>` 做索引（JTS `Geometry.equals()` 是几何相等，不是引用相等）
- `MaxSpanningTree.compute(n, edges)` 要求图连通；真实数据按村（`villageCode`）分组后，每村分别建图

### 当前进度与待实现

**已完成（Task A）：** QC 链路完整，smoke test 验证 65,387 个图斑，发现 8 个 `Ring Self-intersection`，8 个村。

**待实现（Task B）：** 均衡切分。

业务目标：给定一个村的全部地块，生成"任务包"供外业人员领取。约束：
1. 每包地块数 ≤ `maxGroupSize`（上限阈值，如 1000）
2. 每包内地块在空间上**连续**（不能把不相邻的地块放同一包）
3. 各包大小**均衡**（不允许一包 10000 个、另一包 3 个）

`PartitionCutter` 只满足约束 1/2，不满足约束 3（切弱边可能产生极端不均衡）。
目标 API：`VillagePartitioner.partition(List<Plot> villagePlots, int maxGroupSize) → List<List<Plot>>`

参考 `study` 包的均衡切分逻辑：
- `TreePartition.dfsSubtreeSize()` — 迭代 DFS，计算子树大小
- `CutEdge.findBestBalancedCut()` — 找切后两侧大小差最小的边
- `TreeCutResult.cutOnce()` — 一次均衡二分，返回 partA / partB

Task B 实现步骤：在 `partition.cut` 包新建类，TDD 驱动，先用小测试验证均衡性，再接入真实村数据。

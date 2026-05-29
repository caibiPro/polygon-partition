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
  └─ ShapefileReader.readFeatures()           → List<SimpleFeature>
       └─ SichuanPlotMapper.map()             → List<Plot>        ← 反腐层边界
            ├─ GeometryValidator.validate()   → List<QCReport>    (QC，不修复)
            └─ VillagePartitioner.partition() → List<List<Plot>>  ← 待实现
                 ├─ AdjacencyGraphBuilder.build()      → List<WeightedEdge>（全村邻接图）
                 ├─ UnionFind（找连通分量）              → per-component 节点集
                 │    ├─ 分量 ≤ maxGroupSize → 直接作为一组
                 │    └─ 分量 > maxGroupSize →
                 │         MaxSpanningTree.compute()   → List<WeightedEdge>（MST）
                 │         TreePartitionAlgorithm.partition() → List<List<Integer>>
                 └─ 局部编号 → Plot 映射，输出 List<List<Plot>>
```

### 包职责

| 包 | 职责 |
|---|---|
| `partition.domain` | `Plot` record — 唯一域对象，贯穿所有业务逻辑 |
| `partition.io` | `ShapefileReader` — GeoTools 文件读取，返回 `SimpleFeature` |
| `partition.mapper` | `PlotMapper` 接口 + `SichuanPlotMapper` — 隔离 GeoTools schema |
| `partition.geometry` | `GeometryValidator` / `QCReport` / `NeighborIndex` (STRtree) / `GeometryOps` |
| `partition.graph` | `AdjacencyGraphBuilder` / `MaxSpanningTree` (Kruskal) / `UnionFind` / `WeightedEdge` |
| `partition.cut` | 策略接口：`TreeCutStrategy` / `TargetedPeelingStrategy` / `BalancedCutStrategy` |
| `partition.cut.algorithm` | 算法接口：`TreePartitionAlgorithm` / `RecursivePeelingAlgorithm` / `GlobalWeakestEdgeAlgorithm` |
| `partition.cut.model` | `PartitionContext` / `TreeCutDecision` |
| `partition.pipeline` | 集成测试 + 手动 smoke test |
| `study` | 练习代码，不被 production 代码依赖 |

### 关键设计约束

- `SimpleFeature`（GeoTools）不得进入 `geometry` / `graph` / `cut` 包，必须先 map 成 `Plot`
- `GeometryOps.sharedBoundaryLength` 不做 `buffer(0)` 修复，TopologyException 由调用方处理（SRP）
- `AdjacencyGraphBuilder` 用 `IdentityHashMap<Geometry, Integer>` 做索引（JTS `Geometry.equals()` 是几何相等，不是引用相等）
- `MaxSpanningTree.compute(n, edges)` 要求图连通；连通分量拆分由 `VillagePartitioner` 负责，不在 MST 层处理
- `TreePartitionAlgorithm` 只接受单个连通分量的 MST（局部编号 0..n-1），不感知多分量场景

### 当前进度

**已完成（Task A）：** QC 链路完整，smoke test 验证 65,387 个图斑，发现 8 个 `Ring Self-intersection`，8 个村。

**已完成（Task B 算法层）：** 切分算法框架完整，全部测试通过。
- `RecursivePeelingAlgorithm` + `TargetedPeelingStrategy`：目标尺寸递归剥离，解决 50/50 过度切分问题
- `GlobalWeakestEdgeAlgorithm`：切 k-1 条最弱边，适用于已知分组数场景
- 策略可扩展：实现 `TreeCutStrategy` 接口即可插入新切点选择逻辑

**待实现（Task B 集成层）：** `VillagePartitioner`

业务目标：给定一个村的全部地块，生成"任务包"供外业人员领取。约束：
1. 每包地块数 ≤ `maxGroupSize`（上限阈值，如 500）
2. 每包内地块在空间上**尽量聚合**（软约束，不严格要求连续）
3. 各包大小**均衡**（目标：组间差 < 50 个图斑）

`VillagePartitioner` 实现步骤：
1. 接收 `List<Plot>` + `maxGroupSize`，提取 `geometry` 列表
2. `AdjacencyGraphBuilder.build()` 建全村邻接图
3. `UnionFind` 找连通分量，分量 ≤ maxGroupSize 直接成组
4. 分量 > maxGroupSize：`MaxSpanningTree.compute()` + `RecursivePeelingAlgorithm.partition()`
5. 局部编号映射回 `Plot`，输出 `List<List<Plot>>`
6. **小分量合并**（大概率必须）：实测数据约 2,831 个连通分量、平均 ~23 图斑/分量；直接成组会产生大量过小任务包。需贪心装箱将小分量合并至接近 maxGroupSize。

**验收不变量**：每个 `Plot` 出现且仅出现一次（集合划分，无遗漏无重复），地理边界不重合由此自动保证。

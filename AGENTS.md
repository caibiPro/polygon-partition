# AGENTS.md

本文件为在该仓库工作的 AI agent 提供项目级指导。

## Communication

- 始终使用自然、专业的简体中文回复。
- 技术术语在更清晰或与源码名称匹配时保留英文。
- 不使用 emoji。

## Commands

```bash
# 全量验证
./mvnw clean test
```

通过标准：

```text
Failures: 0, Errors: 0
BUILD SUCCESS
```

测试数量会随测试类拆分和新增变化，不作为文档基线；以最新 Maven 输出为准。

```bash
# 运行单个测试类
./mvnw test -Dtest=AdjacencyGraphBuilderTest

# 运行手动真实数据 smoke test（默认 @Disabled）
./mvnw test -Dtest=RealDataSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
```

真实数据 Shapefile 路径：`~/Documents/files/shapefiles/四川测试数据/地块数据.shp`

## Problem Domain

farmland polygon partitioning（农田地块空间分组）。

业务目标：给定 farmland polygon Shapefiles，按村（字段 `CUNDM`）分组，生成大小均衡且空间连通的外业任务包。

约束：
- 每包地块数 ≤ `maxGroupSize`
- 包内地块空间上尽量聚合（连通性软约束）
- 同村内各包大小均衡（避免极端分裂）

## Architecture

核心 pipeline：

```text
Shapefile (.shp)
  -> ShapefileReader.readFeatures()           -> SimpleFeature（流式读取）
  -> SichuanPlotMapper.map()                  -> Plot（schema 边界）
  -> GeometryValidator.validate()             -> QCReport（仅报告，不修复）
  -> 按 villageCode 分组
  -> VillagePartitioner.partition()
       ├─ AdjacencyGraphBuilder               -> 全村邻接图（STRtree + sharedBoundaryLength）
       ├─ UnionFind（连通分量拆分）
       │    ├─ 分量 ≤ maxGroupSize -> 暂存，进入合并阶段
       │    └─ 分量 > maxGroupSize ->
       │         MaxSpanningTree.compute()    -> MST
       │         RecursivePeelingAlgorithm    -> 递归剥离切分
       ├─ MortonOrder.sort()                  -> 小分量按 Morton 曲线排序
       └─ SpatialMerger.merge()               -> 装箱合并成任务包
  -> 局部编号映射回 Plot                      -> List<List<Plot>>
```

包职责：

| Package | Responsibility |
|---|---|
| `com.mingqing.partition.domain` | 域对象，核心为 `Plot` record。 |
| `com.mingqing.partition.io` | 外部文件 I/O：`ShapefileReader` / `ShapefileWriter`。 |
| `com.mingqing.partition.mapper` | 外部 schema 到域对象的防腐层（`PlotMapper` / `SichuanPlotMapper`）。 |
| `com.mingqing.partition.geometry` | 几何验证（`GeometryValidator` / `QCReport`）、STRtree 索引（`NeighborIndex`）、几何运算（`GeometryOps`）。 |
| `com.mingqing.partition.graph` | 图原语与算法：`AdjacencyGraphBuilder`、`MaxSpanningTree`、`UnionFind`、`WeightedEdge`、`Subgraph`。 |
| `com.mingqing.partition.cut` | 切分策略接口与实现：`TreeCutStrategy`、`TargetedPeelingStrategy`、`BalancedCutStrategy`。 |
| `com.mingqing.partition.cut.algorithm` | 图分割算法：`TreePartitionAlgorithm`、`RecursivePeelingAlgorithm`、`GlobalWeakestEdgeAlgorithm`。 |
| `com.mingqing.partition.cut.model` | 分割参数与决策对象：`PartitionContext`、`TreeCutDecision`。 |
| `com.mingqing.partition.merge` | 小分量合并：`MortonOrder`（Morton/Z-order 排序）、`MortonMerger`（balanced-target 装箱）、`LocalBestFitMortonMerger`（窗口 best-fit 装箱）、`SortSweepMerger`（基准对照）、`SpatialMerger` 接口。 |
| `com.mingqing.partition.partitioner` | `VillagePartitioner` — 村级编排，串联构图→切分→合并→映射。 |
| `com.mingqing.study` | 学习/演示代码；生产代码不得依赖。 |

### 合并策略

小连通分量（实测平均 ~23 图斑/分量）需合并为任务包。合并分两步——**排序负责紧凑度，装箱负责均衡**：

- **MortonOrder**：二维坐标量化后位交错编码（Morton/Z-order），按码排序使空间邻近近似转为一维相邻。多个 merger 共用同一排序。
- **MortonMerger**：balanced-target 装箱，沿排序顺序推进达到软目标即封箱。online 装箱，O(n)。
- **LocalBestFitMortonMerger(w)**：窗口化 local best-fit——封箱前向前看 window 个位置，把能塞进剩余空位的小簇就近捞进来填缝，消除大簇"路障"导致的孤儿包。`w=64` 实测性价比最优（std 比 Morton 降 48%，包数少 16%，紧凑度代价 30%）。
- **SortSweepMerger**：按 y 坐标排序装箱，紧凑度最差，作基准对照。

## Design Constraints

- **Schema 隔离**：GeoTools `SimpleFeature` 不得进入 `geometry`、`graph`、`cut`、`merge`；必须先 map 为 `Plot`。
- **字段名边界**：`OBJECTID`、`WYBH`、`CUNDM` 等 Shapefile 字段名属于 `mapper`，不属于核心算法。
- **不静默修复**：无效几何不在 graph building 中自动修复；通过 `GeometryValidator` 和 `QCReport` 报告。
- **共享边长计算**：`GeometryOps.sharedBoundaryLength` 仅计算共享边界长度，不执行 `buffer(0)` 修复；对无效几何抛 `IllegalArgumentException`。
- **身份索引**：`AdjacencyGraphBuilder` 必须使用 `IdentityHashMap<Geometry, Integer>` 将候选几何映射回索引。JTS `Geometry.equals()` 是几何相等，非对象身份相等。
- **MST 前提**：`MaxSpanningTree.compute(n, edges)` 要求输入图连通。实际村庄图必须先拆分为连通分量，再对每个分量调用 MST。
- **算法边界**：`TreePartitionAlgorithm` 只处理单个连通分量的 MST（局部编号 0..n-1），不感知多分量场景。
- **集合划分**：分组结果是集合划分，每个 Plot 出现且仅一次，地理边界不重合由此自动保证。
- **基线保留**：`GlobalWeakestEdgeAlgorithm` 是弱边切分的基线；更改基线行为需显式确认。

## Current State

- **已完成**：
  - Task A：QC 链路完整，smoke test 验证 65,387 图斑，发现 8 个 Ring Self-intersection（江阳区 8 村）。
  - Task B 算法层：`RecursivePeelingAlgorithm` + `TargetedPeelingStrategy`（目标尺寸剥离）+ `GlobalWeakestEdgeAlgorithm`（切 k-1 条最弱边）。策略可扩展：实现 `TreeCutStrategy` 即可插入新切点逻辑。
  - Task B 集成层：`VillagePartitioner` — 连通分量拆分 → MST 切分 → 小分量 Morton 排序 + 装箱合并。三种合并策略可用。
  - Task B 输出：`ShapefileWriter.writePerPackage()` — 逐包写出 Shapefile（`pkg_000.shp`...），回读校验不丢不重。

- **真实数据验证**（65,387 图斑，maxGroupSize=500）：默认推荐 `LocalBestFitMortonMerger(64)` — 135 包，std=60.2，min=50，紧凑度 0.003579。

- **验收不变量**：每包大小 ≤ maxGroupSize，每个 Plot 出现且仅一次（集合划分）。

扩展切分算法：
- 实现 `TreeCutStrategy` 接口即可插入新的切点选择逻辑。
- 添加新 partitioning 行为时，先写纯 graph/tree 单元测试（不涉及 Geometry），稳定后再加集成测试。
- 真实数据 smoke test 保持手动 `@Disabled`，除非变得确定且足够快。

## Working Rules

- 小而专注的编辑，保留现有 Maven / JUnit 5 / AssertJ 设置。
- 使用 `rg` 进行代码搜索。
- 修改后必须运行 `./mvnw clean test`，确认 `Failures: 0`、`Errors: 0` 且 `BUILD SUCCESS`。
- 不提交本地状态文件、IDE workspace 文件、build outputs、agent handoff scratch 文件。
- 跨会话引用使用稳定的 feature names、package boundaries、算法约束，而非临时任务编号或对话本地标签。

## Related Documents

- `doc/problem-and-architecture.md` — 深度教学文档：问题建模推导、5 种方案对比、MST 选型理由、改进/升级方向

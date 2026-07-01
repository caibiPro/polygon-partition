# 村域图斑均衡切分：从问题建模到工程实现

## 1. 业务问题

输入是一批农田地块 Shapefile，每个图斑携带行政属性（`CUNDM` = 村编码）。目标是按村生成"任务包"供外业人员领取：

- 每包图斑数 ≤ 上限阈值（如 500）
- 同包内地块空间上**尽量聚合**（软约束，不严格要求连续）
- 同一村拆出的多个包大小**尽量均衡**（组间差尽量小）

问题的难点在于：图斑分布往往不规则、存在大量零散小碎片、部分村域空间上不连续。要在这些约束下做到"既聚拢又均衡"，需要一个从建模到算法到工程的完整思考链。

---

## 2. 思维演进：从极简模型到真实场景

理解一个难问题，最好的办法是先把它简化到极致，再一层一层加约束。这条思维链本身就值得内化。

### 2.1 极简模型：一维连续分段

先假设不是二维图斑，而是一排排好序的小区间。要求把这一维序列切成若干段，每段元素数不超过阈值 `T`。

如果目标只有"每段不超过 `T`"，那最优块数就是 `k = ceil(n / T)`。如果还要"尽量均匀"，最自然的目标就是让每段大小尽量接近 `n / k`。在一维有顺序约束时，贪心或 DP 都能做得很干净，因为**"连续"本身就替你保证了连通性**。

这一层有两个关键认知：

> **第一，最优之前必须先定义目标。** 如果只说"每块都小于阈值"，那把每个图斑单独分一块也满足约束，但显然不是你想要的。所以必须再加目标——先让块数最少（`k = ceil(N / T)`），在此约束下再尽量均匀。

> **第二，顺序一旦消失，难度会陡增。** 一维有天然顺序；二维没有。

### 2.2 二维网格模型：NP-hard 的根源

把一维换成二维网格，每个单元和上下左右相邻。现在要把网格分成若干个**连通**子集，每个子集大小不超过 `T`，还希望尽量均匀。

这时问题本质已经变成了**连通平衡图划分**（connected balanced partition）。文献中这类问题在一般图上是 NP-hard——Moura 等 2023 年明确证明，带权 connected k-partition 的 min-max / max-min 版本对任意固定 `k ≥ 2` 都是 NP-hard。这意味着，对 10 万级图斑求全局精确最优，理论上就不现实。

### 2.3 真实场景：几何层 + 组合优化层

真实问题有两层：

- **组合优化层**：哪些图斑分到同一组——这是图划分问题
- **几何重建层**：每组图斑如何重新变成一个合法子边界——这是 union 问题

经典 polygon partition（如 CGAL `Partition_2`）研究的是把简单多边形分解为 y-monotone 或 convex 子多边形，输入是边界点序列，而非"内部有大量不可切分的图斑"。它对"不能切内部图斑"这个业务约束并不直接适配。

所以这个问题最合适的抽象不是"多边形切割"，而是：

> **把图斑当作面单元，构建平面覆盖的对偶图，然后做连通平衡划分。**

这是整个系统设计的根假设——后面所有架构决策都从这里展开。

---

## 3. 正确建模：把图斑变成图

### 3.1 图的定义

设每个图斑是一个节点 `v`。如果两个图斑**共享一段边界**（不是只在一点接触），就在它们之间连一条边 `e`。边权 = 共边长度。

**为什么用共边长度做边权？** 直觉：共享边长越长，两个图斑"绑定"越强。如果切掉一条很长的接触边，对子区块形状破坏更大；切断短边通常更自然。所以边权代表"不应该被切断的强度"。

### 3.2 为什么必须先固定 k

一个容易被忽略但极其重要的点：**如果不先固定 k 或不惩罚块数，"最优"是没有定义的。**

因为"只要求 ≤ T"意味着每个图斑单独成块永远可行；如果又说"尽量均匀"，大量小块反而更容易"均匀"。

因此工程上固定 `k = ceil(N / T)`，在这个 k 下优化均衡和切割质量。这与图划分工具的默认目标一致：给定 k，尽量平衡各块大小，同时最小化 cut。KaHIP 和 METIS 都是这个范式。

### 3.3 词典序目标

这个问题的优化目标适合用**词典序**（lexicographic ordering）而非加权打分来定义：

| 优先级 | 类别 | 内容 |
|--------|------|------|
| 1 | 硬约束 | 每包图斑数 ≤ T、每个图斑出现且仅一次 |
| 2 | 主目标 | 包数最少，k = ⌈N / T⌉ |
| 3 | 次目标 | 各包大小尽量均衡（std / max-min） |
| 4 | 形状目标 | 尽量少切断长共边、紧凑度优先 |

词典序的优势：**不会出现"为了更圆一点，结果多切一块"的反直觉结果。** 高层目标永远优先于低层目标。

---

## 4. 方案全景对比

在实际选择了图划分路线之前，我们探索了多种可能的方案。理解"哪些路走不通、为什么走不通"与理解"哪条路走得通"同样重要。

### 4.1 直接几何切线（KD 树 / 四叉树递归二分）

**思路**：按空间位置切村边界——横切、竖切、沿包围盒长边递归二分。每次切分保证两侧图斑数尽量接近。

**直觉上一开始最容易想到这个方案**，因为它在"点数据"场景下非常好用：给平面上一堆点，按 KD 树递归二分直到每块点数 ≤ T，O(n log n) 干净利落。

**问题出在"对象不是点而是不可切分的多边形"**：

切分线可以穿过图斑，但你不能真的把图斑切开。于是必须引入分配规则——比如"按质心归属"——但一个大图斑可能大部分在右边、质心却在左边，导致"空间切线"和"最终子边界"很不一致。

更致命的是，即使按质心正确分配了图斑到各个叶子，**同一叶子里的图斑也未必连通**。纯空间划分只能保证"空间接近"，不能保证"拓扑连通"。于是必须再加一层连通性修复——把不连通的叶子拆成连通分量，把离群碎片迁移给邻近组——修复做到最后，算法已经不是"简单的 KD 树"了，而是退化成了一个复杂的图划分变体。

**这个方案的核心教训**：

> **空间划分解决的是"怎么先分快"，不解决"怎么分得对"。** 空间划分与拓扑正确，天然不是一回事。

这个路线在项目早期作为基准探索过（见 `doc/02-空间基准方案/`），最终因为"修复成本等同重做"而放弃。但它的学习价值在于：**先学空间划分为什么不够，再去学图划分解决了什么**——这个对比本身就值得深刻理解。

### 4.2 Hilbert / Z-order 空间填充曲线排序后分桶

**思路**：用空间填充曲线（Morton / Hilbert）把每个图斑的二维坐标编码为一维 Morton 码，按码排序使"二维邻近"近似转成"一维相邻"，沿一维顺序贪心分组。

**原理**：Morton 码（Z-order）的做法是把 x、y 坐标量化后做**位交错**——把 x 的每一位散开到偶数位，y 的每一位散开到奇数位，交错合并。结果是：空间上邻近的点，Morton 码也相近。

**优势**：实现非常快，O(n log n)，无距离阈值/无网格尺寸等参数。在 GIS / 空间数据库分片中是通用做法。

**局限**：

- Morton 在 2×2 块切换处有**对角跳变**——空间上相邻的两个位置，Morton 码可能差很多。Hilbert 曲线能消除跳变，但编码实现更复杂。
- **只能近似连通，不保证连通**——可能出现蛇形分块。
- 最终 union 可能得到 MultiPolygon 或细长怪形。

**在项目中的角色**：排序思想被吸纳进小分量合并阶段（`MortonOrder`）——在切分完成后，把大量零散小连通分量按 Morton 顺序排列、再装箱合并成任务包。排序负责紧凑度，装箱负责均衡，各司其职。

### 4.3 贪心区域生长（region growing）

**思路**：从若干种子图斑出发，同时向外扩张（每次把与当前组邻接的未分配图斑纳入），直到每组达到目标大小。

**优势**：天然不切图斑，容易保持连通。

**局限**：强依赖种子选取（种子选在哪、选几个、选多远）和生长顺序（多个组同时抢一个边界图斑时归谁），质量波动大，容易陷入局部最优——一个组可能长成奇怪的形状，而另一个组被憋在墙角。

**判断**：作为工程启发式不错，但不是推荐的第一版。

### 4.4 邻接图上的递归平衡二分（采用方案）

**思路**：先构建图斑邻接图，再做递归二分，直到每块都不超过阈值。

**优势汇总**：

- 约束最贴合业务——不切图斑是构造保证的
- 连通性可在图上直接校验
- 几何重建简单——每组图斑直接 union
- 可扩展——切分策略可插拔，MST 可替换为 KaHIP 等更高级划分器

**这是当前 production 方案的基础**：先建 MST 做降维，在树上递归切分。

### 4.5 ILP / CP-SAT 精确优化

**思路**：每个图斑属于哪个块 → 0/1 决策变量，再加连通性约束、容量约束、cut 最小化目标，交给整数规划求解器。

**优势**：理论上能出真正最优。

**局限**：10k~100k 规模基本不现实。变量数和约束数随图斑数爆炸。可行路径是先 coarsen 成很小的超点图，在粗图上精确求解，再展开 refinement。

**定位**：适合作为"小规模基准"或"coarsen 后 refinement"，不适合直接求解全量数据。

---

## 5. 为什么用 Maximum Spanning Tree 降维

### 5.1 根源动机

直接在一般图上做最优连通平衡划分（connected k-partition）是 NP-hard。MST 是一种工程化降维——**把带环的图简化为一棵树，在树上切分保证连通且易于计算**。

**为什么要建"最大"而不是"最小"生成树？** 这个问题值得反复咀嚼。

```
最小生成树保留了: 1, 2, 3, 4, 5, 5, 6, 6 (最小的 N-1 条边，即最弱的连接)
  → 树上最强的边权 = 6
  → 切割时可能切掉权重 6 的边 → 把共边长度 6 的相邻图斑分开了 → 对形状破坏大

最大生成树保留了: 10, 9, 8, 8, 7, 7, 6, 6 (最大的 N-1 条边，即最强的连接)
  → 树上最弱的边权 = 6
  → 切割时优先切权重 6 的边 → 只从共边最短的地方分开 → 对形状破坏最小
```

**直觉**：共享边长越长，两个图斑"绑定"越强。最大生成树尽量把强关系留在树里。最终切树时优先切短弱边，通常更自然。这是一种很典型的"先把难问题降到树上"的思想。

**为什么不用最小割（Stoer-Wagner 等）？** 最小割本身只考虑 cut 小，不考虑 balance，也不保证未来还能继续拆。它更适合用作局部 refinement 或小图二分子步骤，而非完整的连通平衡 k 分块方案。

### 5.2 MST 方案逐步拆解

用一个 9 图斑的例子把整个流程走一遍：

```
Step 1 — 邻接图（每个格子是一个图斑）：

    ┌───┬───┬───┐
    │ A │ B │ C │    边上的数字 = 共享边界长度
    ├───┼───┼───┤    共边越长 → 两个图斑在空间上绑定越紧
    │ D │ E │ F │
    ├───┼───┼───┤
    │ G │ H │ I │
    └───┴───┴───┘

    A—8—B—5—C
    |   |   |
    4   9   6
    |   |   |
    D—7—E—10—F
    |   |   |
    3   8   5
    |   |   |
    G—6—H—7—I

Step 2 — Kruskal 最大生成树：

    边按权重降序: E-F(10), B-E(9), A-B(8), E-H(8), D-E(7), H-I(7), ...

    贪心选边（不构成环就选）：

    Round 1: E—10—F  ✓    Round 5: D—7—E   ✓
    Round 2: B—9—E   ✓    Round 6: H—7—I   ✓
    Round 3: A—8—B   ✓    Round 7: C—6—F   ✓
    Round 4: E—8—H   ✓    Round 8: G—6—H   ✓

    8 条边 = 9 节点 - 1，Done。

    最大生成树:
        A—8—B   C
        |   |   |
        ×   9   6        × = 被丢弃的边（弱连接）
        |   |   |
        D—7—E—10—F
            |
            8
            |
        G—6—H—7—I

    注意：树保留了最强的连接。被丢弃的都是相对较弱的边。

Step 3 — 递归切树（假设阈值 K=3）：

    需要切成 ⌈9/3⌉ = 3 个区域。

    遍历每条树边，计算删除后两侧大小和均衡度：

    删A-B: [A](1) vs [B..I](8)      差=7
    删B-E: [A,B](2) vs [C..I](7)    差=5
    删C-F: [C](1) vs [A,B,D..I](8)  差=7
    删D-E: [D](1) vs [A..C,E..I](8) 差=7
    删E-F: [A,B,D,E](4) vs [C,F,G,H,I](5) 差=1 ← 最均衡！
    删E-H: [A..F](6) vs [G,H,I](3)  差=3 ← 右侧达标可停
    删G-H: [G](1) vs [A..F,H,I](8)  差=7
    删H-I: [I](1) vs [A..H](8)      差=7

    选E-H（均衡且右侧达标）:
      左侧 {A,B,C,D,E,F} = 6 个，递归
      右侧 {G,H,I} = 3 ≤ K，停

    对左侧继续:
      删B-E: [A,B](2) vs [C,D,E,F](4) 差=2
      删E-F: [A,B,D,E](4) vs [C,F](2) 差=2
      选B-E: 左侧 {A,B}=2≤K停，右侧 {C,D,E,F}=4>K 再递归...

    最终:
      区域1: {A, B}
      区域2: {C, D, E, F} → 再切 → {C, F}, {D, E}
      区域3: {G, H, I}

    每个区域内的图斑保证连通 ← 这是树的核心性质！
```

**关键洞察**：树上切一条边一定得到两个连通子树——连通性不需额外校验。MST 把"连通"这个最难保证的属性变成了构造属性。

### 5.3 递归切分中的"按 k 估算目标"技巧

递归时不是简单 50/50 对半劈。例如当前块有 2500 个图斑、阈值 1000：

- k = ceil(2500/1000) = 3，理想大小接近 833/1667
- 如果硬劈成 1250/1250，后面会得到 4 块（多一块）
- 正确做法：先估当前块最少需要几块，把"未来块数"纳入切分目标

这是从"局部平衡"走向"全局块数最优"的关键技巧。

### 5.4 切分策略的选择

项目实现了两种策略，适用于不同场景：

- **TargetedPeelingStrategy**（主策略）：每次从树边缘剥离一个大小尽量接近 maxGroupSize 的子树。与 50/50 均衡切相比，避免在接近阈值时过度切分，组间差更小。适用场景：按已知阈值拆包。
- **BalancedCutStrategy**：每次 50/50 均衡二分，递归深度 O(log k)。适用场景：追求深度平衡。
- **GlobalWeakestEdgeAlgorithm**：切 k-1 条最弱边，适用于已知分组数场景。

---

## 6. 处理链路

```text
Shapefile (.shp)
  └─ ShapefileReader.readFeatures()           → SimpleFeature（流式读取）
       └─ SichuanPlotMapper.map()             → Plot（隔离 GeoTools schema）
            ├─ GeometryValidator.validate()   → QCReport（只报告，不修复）
            └─ VillagePartitioner.partition()
                 ├─ AdjacencyGraphBuilder     → 全村邻接图
                 │    └─ STRtree + sharedBoundaryLength 判定
                 ├─ UnionFind                 → 找连通分量
                 │    ├─ 分量 ≤ maxGroupSize  → 暂存，进入合并阶段
                 │    └─ 分量 > maxGroupSize  → MST + RecursivePeeling
                 ├─ SpatialMerger.merge()     → 小分量 Morton 排序 + 装箱
                 └─ 局部编号 → Plot 映射     → List<List<Plot>>
```

**数据分层的设计原则**：GeoTools 的 `SimpleFeature` 不得进入 `geometry` / `graph` / `cut` / `merge` 包，必须先通过 `PlotMapper` 映射为 `Plot`。这让核心算法与文件格式解耦——如果未来数据源从 Shapefile 换成 GeoJSON 或数据库，只需替换 `io` 和 `mapper` 层。

### 关键算法与复杂度

| 步骤 | 复杂度 | 说明 |
|------|--------|------|
| STRtree 建索引 | O(n log n) | 对图斑 envelope 建索引，避免 O(n²) 两两比较 |
| 邻接图构建 | O(n log n + P × g) | P=candidatePairs, g=单次几何边界计算成本 |
| 连通分量 | O(n + E) | UnionFind，几乎线性 |
| MaxSpanningTree | O(E log E) | Kruskal，边权降序 |
| RecursivePeeling | O(V² / maxGroupSize) | 每轮 DFS + 全扫描 |
| Morton 排序 | O(n log n) | 位交错编码 |
| LocalBestFit 装箱 | O(n · window) | window 是常数 |

三种方案（坐标二分、MST 切分、METIS 图划分）的渐近复杂度相同——都是 O(n log n)，差异在常数因子和实现质量。

---

## 7. 包职责与关键设计约束

### 7.1 包结构

| 包 | 职责 |
|---|---|
| `partition.domain` | `Plot` record — 唯一域对象，贯穿所有业务逻辑 |
| `partition.io` | Shapefile 读写，流式处理 |
| `partition.mapper` | `PlotMapper` 接口 + `SichuanPlotMapper`，隔离 GeoTools schema |
| `partition.geometry` | `GeometryValidator` / `QCReport` / `NeighborIndex`(STRtree) / `GeometryOps` |
| `partition.graph` | `AdjacencyGraphBuilder` / `MaxSpanningTree`(Kruskal) / `UnionFind` / `WeightedEdge` |
| `partition.cut` | 策略接口：`TreeCutStrategy` / `TargetedPeelingStrategy` / `BalancedCutStrategy` |
| `partition.cut.algorithm` | `RecursivePeelingAlgorithm` / `GlobalWeakestEdgeAlgorithm` |
| `partition.merge` | `MortonOrder` + `MortonMerger` / `LocalBestFitMortonMerger` / `SortSweepMerger` |
| `partition.partitioner` | `VillagePartitioner` — 村级编排入口 |
| `study` | 练习代码，不被 production 依赖 |

### 7.2 关键设计约束

- **数据边界**：`SimpleFeature` 不进核心算法包，必须先 map 成 `Plot`
- **SRP（单一职责）**：`GeometryOps.sharedBoundaryLength` 不做 `buffer(0)` 修复，`TopologyException` 由调用方处理
- **索引语义陷阱**：`AdjacencyGraphBuilder` 用 `IdentityHashMap<Geometry, Integer>` 而非普通 `HashMap`——因为 JTS 的 `Geometry.equals()` 是几何相等而非引用相等，两个坐标完全相同但属于不同图斑的 geometry 会被错误合并
- **MST 层职责**：`TreePartitionAlgorithm` 只接受单个连通分量的 MST（局部编号 0..n-1），不感知多分量——连通分量拆分由 `VillagePartitioner` 负责
- **不变量保证**：分组结果是集合划分——每个 Plot 出现且仅一次，地理边界不重合由集合划分自动保证，无需单独校验

### 7.3 CoverageUnion + Fallback 的设计

JTS `CoverageUnion` 对 valid polygonal coverage 更高效，但前提是输入 vector-clean 且 interior-disjoint。生产数据可能不满足。代码的策略是先尝试 `CoverageUnion`，失败后退回 `UnaryUnionOp`。这不是绕过 QC——QC 在 pipeline 入口做——而是 union 阶段不做静默修复，让异常可见。

---

## 8. 当前实现状态

### 8.1 已完成

| 阶段 | 内容 |
|------|------|
| Task A (QC) | 读取 → 映射 → 校验。Smoke test 验证 65,387 图斑，发现 8 个 Ring Self-intersection（江阳区 8 个村） |
| Task B (算法) | `RecursivePeelingAlgorithm` + `TargetedPeelingStrategy`（目标尺寸剥离）+ `GlobalWeakestEdgeAlgorithm` |
| Task B (集成) | `VillagePartitioner` — 按连通分量拆分 → MST 切分 → 小分量 Morton 合并 |
| Task B (合并) | 三种 merger：`MortonMerger` (balanced-target)、`LocalBestFitMortonMerger` (窗口 best-fit)、`SortSweepMerger` (基准对照) |
| 输出 | Shapefile 逐包写出（`pkg_000.shp`...），回读校验不丢不重 |

### 8.2 真实数据验证（65,387 图斑，maxGroupSize=500）

| 策略 | 包数 | std | min | max | 紧凑度(↓) |
|------|------|-----|-----|-----|-----------|
| **LocalBestFit(w=64)** | **135** | **60.2** | 50 | 500 | 0.003579 |
| LocalBestFit(w=128) | 134 | 51.4 | 50 | 500 | 0.003619 |
| Morton (balanced-target) | 160 | 116.0 | 2 | 500 | **0.002755** |
| SortSweep | 160 | 107.4 | 9 | 500 | 0.005061 |

### 8.3 MortonMerger vs LocalBestFitMortonMerger

两者共用 `MortonOrder.sort()`——紧凑度的来源完全一样。区别**仅在于装箱策略**，这是"排序 ⊥ 装箱"解耦设计带来的灵活性。

**MortonMerger** 是 online 装箱——沿 Morton 顺序单向推进，不回头、不跳跃，达到 soft target 或触及 hard capacity 即封箱，封箱后永不重开。问题：大簇充当"路障"。例如一个 size=499 的簇后面漏出 3 个小簇（size 各 2~5），即使前面的包 slack=20，也无法跨过 499 去吸收——箱子已封。结果为 min=2 的孤儿包。

**LocalBestFitMortonMerger** 是窗口化 offline 装箱——每个包以第一个未用簇为起点，向前看 `window` 个位置，把能塞进剩余空位的任何小簇就近捞进来填缝。`window` 是"均衡 ⊥ 紧凑"的唯一旋钮：

- 越小 → 越接近严格顺序（最紧凑，但孤儿多）
- 越大 → 填料范围越广（越均衡，但跨 Morton 顺序越远 → 紧凑度下降）

真实数据上的验证印证了这个权衡：w=64 是性价比最优——std 从 116 降到 60（改善 48%），包数从 160 降到 135（少 25 个外业人员），紧凑度仅退化 30%。w 继续增大到 128，改善已经边际递减。

**推荐默认：`LocalBestFitMortonMerger(64)`。**

---

## 9. 改进与升级方向

### 9.1 路线一：树切分 + 局部 refinement（最低成本）

在现有 MST 切分得到可行解后，对边界图斑做局部交换优化：

- 找分区边界上的图斑
- 尝试把某个边界图斑从 A 挪到 B
- 前提：A 去掉它后仍连通、B 加入它后仍连通、不超阈值
- 如果 cutLength 更小或均衡更好，接受移动

这是图划分里 local search 的经典套路——Kernighan-Lin 的核心思想就是反复交换边界节点来减少 cut。实现成本低，但能有效缓解 MST 近似带来的次优切分。

### 9.2 路线二：对接 KaHIP 做高质量连通划分

如果接受外部二进制（非纯 Java），流程变为：

1. Java/JTS 构建图斑邻接图
2. 导出为 METIS/KaHIP 图格式
3. KaHIP 做 `k = ceil(N / T)` 划分（v3.25+ 含实验性 `--connected_blocks`）
4. Block ID 回写图斑
5. 每个 block 做 `CoverageUnion` 重建几何

KaHIP 的品质优势来自 multilevel 架构——粗化时用 heavy-edge matching 合并紧密相连的节点对，在粗图上决定全局结构，展开时用 KL/FM 逐层修正局部细节。这种"全局在粗图上快速决定、局部在细图上精确修正"的智慧，是理解所有高质量图划分器的关键。

### 9.3 路线三：Coarsen + 精确求解

如果追求"尽量接近真正最优"：先把紧密相连的图斑聚成超点（coarsen），在超点图（规模从 10000 → 50）上用 ILP/CP-SAT 求精确解，最后展开 refinement。这条路线实现复杂度最高，适合在已有 baseline 并需要严格评估"离最优还有多远"时使用。

### 9.4 生产构图优化：边段哈希法

当前 `AdjacencyGraphBuilder` 用 STRtree + `sharedBoundaryLength` 做通用版构图。如果确认数据共边坐标完全一致、拓扑干净，可替换为**边段哈希法**：

1. 遍历每个图斑的所有边段
2. 边段方向规范化（A→B 和 B→A 视为同一段）
3. 用边段 key 做哈希
4. 同一边段出现两次 → 这两个图斑相邻
5. 累加边长得到共边长度

复杂度更接近 O(S)（S = 总边段数），通常比 STRtree + 边界相交计算更快且更稳。这里的工程原则值得记住：

> **当业务数据已经自带拓扑结构时，不要再拿通用几何算法硬算一遍。**

### 9.5 方法论总结

这道题最值得内化的不是代码，而是几个思维习惯：

1. **先分清硬约束和优化目标**。硬约束前置到建模层，构造时天然满足，而非求解后修补。
2. **遇到几何问题先问：这其实是不是图问题？** 表面切多边形，实质是平面图连通平衡划分。
3. **没有明确目标函数，"最优"是空话。** 只有阈值约束时问题甚至会退化。
4. **先做"正确且可解释"的基线，再追求高质量。** MST 切分不是最强，但它易实现、易验证、易解释、易扩展。
5. **复杂度分析不只写大 O。** 真正的瓶颈在哪？通用算法为何慢？业务数据有什么结构能利用？——这些才是工程上真正的分水岭。

---

## 10. 关键参考

- **Moura, Ota, Wakabayashi (2023)**. *Balanced Connected Partitions* — hardness, approximation, parameterization 结果。确认带权 connected k-partition 对任意固定 k ≥ 2 是 NP-hard。
- **KaHIP** — 高质量平衡图划分框架，multilevel coarsen/refine + 实验性 `--connected_blocks`。适合理解"专业级"图划分是怎么做的。
- **METIS** — 经典多层图划分框架，multilevel recursive bisection / k-way。比 KaHIP 更早但仍然是该领域的基准。
- **《Computational Geometry: Algorithms and Applications》** — 计算几何经典教材，polygon partition、range searching、quadtrees 等基础概念的系统性参考。
- **JTS (Java Topology Suite)** — Java 侧几何基础库：`STRtree`、`CoverageUnion`、`Polygonizer`、`IsValidOp`。
- **CGAL `Partition_2`** — 经典 polygon partition 实现，帮助区分"多边形切割"和"图斑不可切分组"两类问题。

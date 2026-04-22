那我直接给你一版“适合作为基准学习”的设计稿和单文件 Java 示例。

先定目标，不然基准会越做越复杂。

这次只做两版：

A 版：纯 KD 树分组
目标是看“空间二分”本身的行为，不保证连通。

B 版：KD 树分组 + 连通拆分
目标是让最终结果至少满足：

1. 不切图斑
2. 每个结果块连通
3. 每块图斑数不超过阈值

这里我故意没有再加“合并回最少块数”的阶段。原因很简单：你现在要先学透第一层骨架，不要一下把“空间切分、连通修复、平衡合并”三件事搅在一起。
等你把 A 和 B 的行为看明白，再做 C 版“贪心合并 / 局部再平衡”会顺得多。

---

## 一、先把这套基准的思想钉住

这套基准不是在“切村边界线”，而是在做两件事：

第一步，按图斑质心做空间递归分桶。
第二步，把桶里的图斑 union 成结果区。

所以最终边界始终来自“完整图斑的并”，不是来自那条 KD 切线本身。
这样天然满足“不把已有图斑切成两半”。

A 版的问题会非常直观：

- 某个桶里的图斑可能不连通
- union 后会得到 `MultiPolygon`
- 分组受坐标轴方向影响很大

B 版就是把这个问题最小化处理：

- 先按 KD 树给初始标签
- 再在每个标签内部，按图斑邻接图拆成连通分量
- 每个连通分量直接作为最终结果块

这样做后，最终结果块一定是连通的，而且不超过阈值。
代价是结果块数量可能比 A 版更多，也通常比理论最少块数更多。

这正是一个很好的基准，因为它能把“空间划分”和“拓扑连通”之间的差异暴露得很清楚。

---

## 二、代码结构为什么这样分

我建议你先只用一个文件，把骨架吃透。不要一开始就拆很多类。

这个文件里我放了这几块：

`Plot`
表示原始图斑，保存 `id / geometry / centroid`

`Region`
表示输出结果块，保存：

- regionId
- sourceLeafId（来自哪个 KD 叶子，便于调试）
- plotIds
- geometry
- plotCount

`partitionKdOnly(...)`
A 版主入口

`partitionKdConnected(...)`
B 版主入口

`kdSplit(...)`
KD 递归切分核心

`buildAdjacency(...)`
构建图斑邻接图

`splitLeafIntoConnectedComponents(...)`
B 版核心：把一个叶子内的图斑拆成连通块

`computeMetrics(...)`
算对比指标，方便你拿 A / B / 后续算法对照

---

## 三、单文件 Java 示例

依赖先只用 JTS。

Maven 依赖可以先这样：

```xml
<dependency>
    <groupId>org.locationtech.jts</groupId>
    <artifactId>jts-core</artifactId>
    <version>1.20.0</version>
</dependency>
```

下面是一版可以直接作为基准实验起点的代码。

```java
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * KD 树基准切分器
 *
 * 版本 A:
 *   - 纯 KD 树质心切分
 *   - 每个叶子图斑数 <= threshold
 *   - 不保证叶子内部连通
 *
 * 版本 B:
 *   - 先做 A
 *   - 再把每个叶子内的图斑，按邻接图拆成连通分量
 *   - 每个连通分量作为最终结果块
 *
 * 适用前提：
 *   1) 原始图斑自身是有效几何
 *   2) 图斑之间没有重叠
 *   3) 图斑之间若相邻，共享边界长度 > 0（只点接触不算邻接）
 *
 * 说明：
 *   这是一版“学习型基准”，重点是结构清晰、行为可解释。
 *   生产优化时，优先替换的是邻接构建部分。
 */
public final class KdTreeVillagePartitioner {

    private static final double EPS = 1e-8;

    private KdTreeVillagePartitioner() {
    }

    public static final class Plot {
        final int id;
        final Geometry geometry;
        final Coordinate centroid;

        Plot(int id, Geometry geometry) {
            this.id = id;
            this.geometry = geometry;
            this.centroid = geometry.getCentroid().getCoordinate();
        }
    }

    public static final class Region {
        public final int regionId;
        public final int sourceLeafId;
        public final List<Integer> plotIds;
        public final Geometry geometry;
        public final int plotCount;

        Region(int regionId, int sourceLeafId, List<Integer> plotIds, Geometry geometry) {
            this.regionId = regionId;
            this.sourceLeafId = sourceLeafId;
            this.plotIds = Collections.unmodifiableList(new ArrayList<>(plotIds));
            this.geometry = geometry;
            this.plotCount = plotIds.size();
        }

        @Override
        public String toString() {
            return "Region{id=" + regionId
                    + ", leaf=" + sourceLeafId
                    + ", plots=" + plotCount
                    + ", geomType=" + (geometry == null ? "null" : geometry.getGeometryType())
                    + "}";
        }
    }

    public static final class Metrics {
        public final int regionCount;
        public final int minPlotCount;
        public final int maxPlotCount;
        public final double avgPlotCount;
        public final double stddevPlotCount;
        public final int disconnectedRegionCount; // geometry union 后是 MultiPolygon 的数量
        public final boolean allUnderThreshold;
        public final boolean allPlotsAssignedExactlyOnce;

        Metrics(int regionCount,
                int minPlotCount,
                int maxPlotCount,
                double avgPlotCount,
                double stddevPlotCount,
                int disconnectedRegionCount,
                boolean allUnderThreshold,
                boolean allPlotsAssignedExactlyOnce) {
            this.regionCount = regionCount;
            this.minPlotCount = minPlotCount;
            this.maxPlotCount = maxPlotCount;
            this.avgPlotCount = avgPlotCount;
            this.stddevPlotCount = stddevPlotCount;
            this.disconnectedRegionCount = disconnectedRegionCount;
            this.allUnderThreshold = allUnderThreshold;
            this.allPlotsAssignedExactlyOnce = allPlotsAssignedExactlyOnce;
        }

        @Override
        public String toString() {
            return "Metrics{" +
                    "regionCount=" + regionCount +
                    ", minPlotCount=" + minPlotCount +
                    ", maxPlotCount=" + maxPlotCount +
                    ", avgPlotCount=" + avgPlotCount +
                    ", stddevPlotCount=" + stddevPlotCount +
                    ", disconnectedRegionCount=" + disconnectedRegionCount +
                    ", allUnderThreshold=" + allUnderThreshold +
                    ", allPlotsAssignedExactlyOnce=" + allPlotsAssignedExactlyOnce +
                    '}';
        }
    }

    /**
     * 版本 A：纯 KD 树切分
     */
    public static List<Region> partitionKdOnly(List<? extends Geometry> geometries, int threshold) {
        validateInput(geometries, threshold);

        List<Plot> plots = toPlots(geometries);
        int n = plots.size();
        int targetLeafCount = ceilDiv(n, threshold);

        List<List<Integer>> leafGroups = new ArrayList<>();
        List<Integer> allIds = IntStream.range(0, n).boxed().collect(Collectors.toList());

        kdSplit(allIds, plots, threshold, targetLeafCount, leafGroups);

        List<Region> result = new ArrayList<>(leafGroups.size());
        for (int i = 0; i < leafGroups.size(); i++) {
            List<Integer> group = leafGroups.get(i);
            Geometry union = unionPlots(group, plots);
            result.add(new Region(i, i, group, union));
        }
        return result;
    }

    /**
     * 版本 B：KD 树切分 + 连通拆分
     *
     * 注意：
     *   这版保证每个最终 Region 连通，但 region 数量可能比 A 版更多。
     *   这是刻意保留的，用来观察“空间划分”和“拓扑连通”之间的差异。
     */
    public static List<Region> partitionKdConnected(List<? extends Geometry> geometries, int threshold) {
        validateInput(geometries, threshold);

        List<Plot> plots = toPlots(geometries);
        int n = plots.size();
        int targetLeafCount = ceilDiv(n, threshold);

        // 1) 先做 KD 初始分组
        List<List<Integer>> leafGroups = new ArrayList<>();
        List<Integer> allIds = IntStream.range(0, n).boxed().collect(Collectors.toList());
        kdSplit(allIds, plots, threshold, targetLeafCount, leafGroups);

        // 2) 构建邻接图
        List<Set<Integer>> adjacency = buildAdjacency(plots);

        // 3) 每个叶子内部拆连通分量
        List<Region> result = new ArrayList<>();
        int regionId = 0;
        for (int leafId = 0; leafId < leafGroups.size(); leafId++) {
            List<List<Integer>> components = splitLeafIntoConnectedComponents(leafGroups.get(leafId), adjacency);
            for (List<Integer> comp : components) {
                Geometry union = unionPlots(comp, plots);
                result.add(new Region(regionId++, leafId, comp, union));
            }
        }
        return result;
    }

    /**
     * KD 递归切分核心。
     *
     * 这里不是简单“对半分”。
     * 我们额外传入 leafBudget（当前子树打算切成几个叶子），
     * 这样可以尽量接近最少块数 ceil(n / threshold)，避免纯二叉切分产生过多叶子。
     */
    private static void kdSplit(List<Integer> ids,
                                List<Plot> plots,
                                int threshold,
                                int leafBudget,
                                List<List<Integer>> outLeaves) {

        int size = ids.size();
        if (size == 0) {
            return;
        }

        // 当前块已经满足阈值，或者预算只允许保留为一个叶子
        if (size <= threshold || leafBudget <= 1) {
            outLeaves.add(new ArrayList<>(ids));
            return;
        }

        Axis axis = chooseAxis(ids, plots);

        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort((a, b) -> {
            double va = axis == Axis.X ? plots.get(a).centroid.x : plots.get(a).centroid.y;
            double vb = axis == Axis.X ? plots.get(b).centroid.x : plots.get(b).centroid.y;
            int cmp = Double.compare(va, vb);
            if (cmp != 0) return cmp;
            return Integer.compare(a, b);
        });

        int leftLeafBudget = leafBudget / 2;
        int rightLeafBudget = leafBudget - leftLeafBudget;

        // 目标左侧大小：按叶子预算比例分配
        int targetLeftSize = (int) Math.round((double) size * leftLeafBudget / leafBudget);

        // 为了确保后续每边都有机会继续切到 <= threshold，这里要满足容量约束
        int minLeftSize = Math.max(1, size - rightLeafBudget * threshold);
        int maxLeftSize = Math.min(size - 1, leftLeafBudget * threshold);

        if (minLeftSize > maxLeftSize) {
            // 正常不应发生；保底退化成中位数二分
            int mid = size / 2;
            List<Integer> left = new ArrayList<>(sorted.subList(0, mid));
            List<Integer> right = new ArrayList<>(sorted.subList(mid, size));
            kdSplit(left, plots, threshold, Math.max(1, leftLeafBudget), outLeaves);
            kdSplit(right, plots, threshold, Math.max(1, rightLeafBudget), outLeaves);
            return;
        }

        int splitIndex = clamp(targetLeftSize, minLeftSize, maxLeftSize);

        List<Integer> left = new ArrayList<>(sorted.subList(0, splitIndex));
        List<Integer> right = new ArrayList<>(sorted.subList(splitIndex, size));

        kdSplit(left, plots, threshold, leftLeafBudget, outLeaves);
        kdSplit(right, plots, threshold, rightLeafBudget, outLeaves);
    }

    /**
     * 选择切分轴：
     *   看当前这批图斑质心的包围盒，长边方向作为切分轴。
     *
     * 这比固定 x/y 交替更符合“空间扩展方向”。
     */
    private static Axis chooseAxis(List<Integer> ids, List<Plot> plots) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (Integer id : ids) {
            Coordinate c = plots.get(id).centroid;
            minX = Math.min(minX, c.x);
            minY = Math.min(minY, c.y);
            maxX = Math.max(maxX, c.x);
            maxY = Math.max(maxY, c.y);
        }

        double width = maxX - minX;
        double height = maxY - minY;
        return width >= height ? Axis.X : Axis.Y;
    }

    /**
     * 构建图斑邻接图。
     *
     * 邻接定义：
     *   两个图斑共享边界长度 > 0
     *   只在一点接触不算邻接
     *
     * 这版是通用基准写法：
     *   STRtree 找候选 + boundary intersection 判断
     *
     * 生产优化时，若数据共边非常规整，建议改成“边段哈希法”。
     */
    private static List<Set<Integer>> buildAdjacency(List<Plot> plots) {
        int n = plots.size();

        List<Set<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new LinkedHashSet<>());
        }

        STRtree index = new STRtree();
        for (int i = 0; i < n; i++) {
            index.insert(plots.get(i).geometry.getEnvelopeInternal(), i);
        }

        for (int i = 0; i < n; i++) {
            Plot a = plots.get(i);

            @SuppressWarnings("unchecked")
            List<Integer> candidates = index.query(a.geometry.getEnvelopeInternal());

            for (Integer j : candidates) {
                if (j <= i) {
                    continue;
                }

                Plot b = plots.get(j);

                if (!a.geometry.getEnvelopeInternal().intersects(b.geometry.getEnvelopeInternal())) {
                    continue;
                }

                double sharedBoundaryLength =
                        a.geometry.getBoundary().intersection(b.geometry.getBoundary()).getLength();

                if (sharedBoundaryLength > EPS) {
                    adjacency.get(i).add(j);
                    adjacency.get(j).add(i);
                }
            }
        }

        return adjacency;
    }

    /**
     * 把一个 KD 叶子内部按邻接图拆成连通分量。
     *
     * 这一步是 B 版的核心。
     * 它非常重要，因为它把“空间近”纠正为“拓扑连”。
     */
    private static List<List<Integer>> splitLeafIntoConnectedComponents(List<Integer> leafPlotIds,
                                                                        List<Set<Integer>> adjacency) {
        Set<Integer> leafSet = new LinkedHashSet<>(leafPlotIds);
        Set<Integer> visited = new HashSet<>();
        List<List<Integer>> components = new ArrayList<>();

        for (Integer start : leafPlotIds) {
            if (visited.contains(start)) {
                continue;
            }

            List<Integer> comp = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            while (!queue.isEmpty()) {
                int cur = queue.poll();
                comp.add(cur);

                for (Integer nb : adjacency.get(cur)) {
                    if (!leafSet.contains(nb) || visited.contains(nb)) {
                        continue;
                    }
                    visited.add(nb);
                    queue.add(nb);
                }
            }

            components.add(comp);
        }

        // 为了便于观察，按组件大小从大到小排序
        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return components;
    }

    /**
     * 计算结果指标，用于 A/B/后续算法对比。
     */
    public static Metrics computeMetrics(int totalPlotCount, List<Region> regions, int threshold) {
        if (regions == null || regions.isEmpty()) {
            return new Metrics(0, 0, 0, 0, 0, 0, true, totalPlotCount == 0);
        }

        int regionCount = regions.size();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        double sum = 0.0;
        int disconnected = 0;
        boolean allUnderThreshold = true;

        boolean[] seen = new boolean[totalPlotCount];
        boolean allPlotsAssignedExactlyOnce = true;

        for (Region region : regions) {
            int c = region.plotCount;
            min = Math.min(min, c);
            max = Math.max(max, c);
            sum += c;

            if (c > threshold) {
                allUnderThreshold = false;
            }

            if (region.geometry != null && region.geometry.getNumGeometries() > 1) {
                disconnected++;
            }

            for (Integer plotId : region.plotIds) {
                if (plotId < 0 || plotId >= totalPlotCount || seen[plotId]) {
                    allPlotsAssignedExactlyOnce = false;
                } else {
                    seen[plotId] = true;
                }
            }
        }

        for (boolean b : seen) {
            if (!b) {
                allPlotsAssignedExactlyOnce = false;
                break;
            }
        }

        double avg = sum / regionCount;
        double variance = 0.0;
        for (Region region : regions) {
            double d = region.plotCount - avg;
            variance += d * d;
        }
        variance /= regionCount;
        double stddev = Math.sqrt(variance);

        return new Metrics(
                regionCount,
                min,
                max,
                avg,
                stddev,
                disconnected,
                allUnderThreshold,
                allPlotsAssignedExactlyOnce
        );
    }

    /**
     * 最基础的结果校验：
     *   1) 图斑是否恰好分配一次
     *   2) 是否超过阈值
     *   3) 结果区之间是否有重叠面积
     *
     * 注意：
     *   A 版 region geometry 可能是 MultiPolygon，这不是 bug，而是我们故意要观测的现象。
     */
    public static List<String> validateRegions(List<Region> regions, int totalPlotCount, int threshold) {
        List<String> errors = new ArrayList<>();
        boolean[] seen = new boolean[totalPlotCount];

        for (Region region : regions) {
            if (region.plotCount > threshold) {
                errors.add("Region " + region.regionId + " 超过阈值: " + region.plotCount);
            }
            for (Integer plotId : region.plotIds) {
                if (plotId < 0 || plotId >= totalPlotCount) {
                    errors.add("非法 plotId: " + plotId);
                    continue;
                }
                if (seen[plotId]) {
                    errors.add("plotId 重复分配: " + plotId);
                }
                seen[plotId] = true;
            }
        }

        for (int i = 0; i < totalPlotCount; i++) {
            if (!seen[i]) {
                errors.add("plotId 未分配: " + i);
            }
        }

        STRtree regionIndex = new STRtree();
        for (Region region : regions) {
            if (region.geometry != null && !region.geometry.isEmpty()) {
                regionIndex.insert(region.geometry.getEnvelopeInternal(), region);
            }
        }

        for (int i = 0; i < regions.size(); i++) {
            Region a = regions.get(i);
            if (a.geometry == null || a.geometry.isEmpty()) {
                continue;
            }

            @SuppressWarnings("unchecked")
            List<Region> candidates = regionIndex.query(a.geometry.getEnvelopeInternal());

            for (Region b : candidates) {
                if (b.regionId <= a.regionId || b.geometry == null || b.geometry.isEmpty()) {
                    continue;
                }

                if (!a.geometry.getEnvelopeInternal().intersects(b.geometry.getEnvelopeInternal())) {
                    continue;
                }

                double overlapArea = a.geometry.intersection(b.geometry).getArea();
                if (overlapArea > EPS) {
                    errors.add("Region " + a.regionId + " 与 Region " + b.regionId
                            + " 存在重叠面积: " + overlapArea);
                }
            }
        }

        return errors;
    }

    private static Geometry unionPlots(List<Integer> plotIds, List<Plot> plots) {
        List<Geometry> geoms = new ArrayList<>(plotIds.size());
        for (Integer id : plotIds) {
            geoms.add(plots.get(id).geometry);
        }
        return UnaryUnionOp.union(geoms);
    }

    private static List<Plot> toPlots(List<? extends Geometry> geometries) {
        List<Plot> plots = new ArrayList<>(geometries.size());
        for (int i = 0; i < geometries.size(); i++) {
            Geometry g = geometries.get(i);
            if (g == null || g.isEmpty()) {
                throw new IllegalArgumentException("第 " + i + " 个图斑为空");
            }
            plots.add(new Plot(i, g));
        }
        return plots;
    }

    private static void validateInput(List<? extends Geometry> geometries, int threshold) {
        if (geometries == null || geometries.isEmpty()) {
            throw new IllegalArgumentException("图斑列表不能为空");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold 必须 > 0");
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum Axis {
        X, Y
    }

    // -------------------------
    // 下面给一个简单的 main 方便你直接试
    // -------------------------

    public static void main(String[] args) {
        GeometryFactory gf = new GeometryFactory();

        // 构造一个简单测试：100 x 100 网格，共 10000 个图斑
        List<Geometry> plots = buildGrid(gf, 100, 100, 1.0, 1.0);
        int threshold = 1000;

        List<Region> a = partitionKdOnly(plots, threshold);
        Metrics ma = computeMetrics(plots.size(), a, threshold);
        List<String> ea = validateRegions(a, plots.size(), threshold);

        System.out.println("=== A版：纯KD树 ===");
        System.out.println(ma);
        if (!ea.isEmpty()) {
            System.out.println("A版校验问题：");
            ea.forEach(System.out::println);
        }

        List<Region> b = partitionKdConnected(plots, threshold);
        Metrics mb = computeMetrics(plots.size(), b, threshold);
        List<String> eb = validateRegions(b, plots.size(), threshold);

        System.out.println("=== B版：KD树 + 连通拆分 ===");
        System.out.println(mb);
        if (!eb.isEmpty()) {
            System.out.println("B版校验问题：");
            eb.forEach(System.out::println);
        }
    }

    private static List<Geometry> buildGrid(GeometryFactory gf, int rows, int cols, double w, double h) {
        List<Geometry> list = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double minX = c * w;
                double minY = r * h;
                double maxX = minX + w;
                double maxY = minY + h;
                list.add(rectangle(gf, minX, minY, maxX, maxY));
            }
        }
        return list;
    }

    private static Polygon rectangle(GeometryFactory gf, double minX, double minY, double maxX, double maxY) {
        Coordinate[] coords = new Coordinate[] {
                new Coordinate(minX, minY),
                new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY),
                new Coordinate(minX, minY)
        };
        return gf.createPolygon(coords);
    }
}
```

---

## 四、这版代码里最该理解透的几个点

### 1）为什么 `kdSplit` 里要传 `leafBudget`

如果你只写成“只要大于阈值就二分”，最后块数经常会偏多。

例如：

- `n = 10001`
- `threshold = 1000`

理论最少块数是 `11`。
但简单二叉递归很容易切成 `16` 块。

所以这里不是机械二分，而是：

- 先算当前这批图斑理论上至少需要几个叶子
- 再按“叶子预算比例”分配左右子树规模

这一步虽然还是简单 KD 树，但已经比“纯对半切”更像一个正经基准。

这也是你后面做更复杂算法时要保留的思维：

**别只看当前一刀，要看这刀切完后，子问题是否还容易满足整体目标。**

---

### 2）为什么 A 版故意不修复不连通

因为你现在做的是“基准”，不是“最终生产解”。

A 版故意保留缺点，才能让你真正看到：

- KD 树的优势是什么
- 它的天然缺陷是什么
- 它在哪些数据分布下会失真

如果你一上来就把各种修补全加上，反而看不清 KD 树本身的行为。

---

### 3）为什么 B 版不是“把小孤岛迁移到别的块”，而是“先拆成连通块”

因为这是最小、最稳、最容易理解的修复。

如果你现在直接做“迁移孤岛到邻居”：

- 会引入容量约束冲突
- 会引入连锁调整
- 会引入局部最优问题

那你学习重点就跑偏了。

而“先拆成连通块”这个动作非常干净：

- 连通性立刻成立
- 图斑不会被切
- 每块数量一定不变小于等于原叶子数量，所以也仍然不超过阈值

缺点只是块数会变多。
但这恰好就是你后面要拿来和更高级算法比较的东西。

---

### 4）为什么邻接定义是“共享边界长度 > 0”

因为“只在一个点接触”通常不能视为同一块自然连通区域。

如果把点接触也当邻接，你会得到很多几何上很脆弱的连通结果，比如：

- 两大块只在一个角点碰一下
- 图上看连通，空间上非常别扭

所以这里故意只认“共边”。

这和你的业务也更贴近，因为地块通常是共边拼接，不是角点拼接。

---

## 五、这套基准怎么测，才能真正学到东西

你不要只跑规则网格。规则网格太“听话”了，不足以暴露问题。

我建议至少测四类。

### 1）规则网格

目的：看基本正确性和复杂度。

例如：

- `100 x 100 = 10000`
- `threshold = 1000`

你应该关注：

- A 版块数
- B 版块数
- A 版 `disconnectedRegionCount`
- B 版 `disconnectedRegionCount`

在规则网格上，A 版通常已经会比较好看，因为数据本身太规整。

---

### 2）带状 / 蛇形分布

构造一条弯曲长条区域内的很多图斑。

这类数据最能暴露 KD 树的问题：

- 它会按坐标轴切
- 结果容易把拓扑上很自然的一整条带切散

你会看到 A 版的 `MultiPolygon` 数量上升，B 版块数也会增加。

---

### 3）多团簇分布

让图斑明显分成几个空间团簇，中间很稀。

这时 KD 树会表现得不错，因为它本来就擅长按空间位置分桶。
这是你以后判断“什么时候 KD 树已经够用”的关键场景。

---

### 4）斜向细长村界

村边界整体是一个斜着的长条。

这能暴露 KD 树的轴敏感性。
你会发现：

- 如果坐标轴方向和实际地块走向不一致
- 切分质量会明显下降

这也是后面图划分比 KD 树强的重要原因之一。

---

## 六、你做实验时建议固定看的指标

不要只看时间，也不要只看“块数”。

建议至少固定这几项：

1. `regionCount`
   最终块数

2. `maxPlotCount / minPlotCount / stddevPlotCount`
   均衡程度

3. `disconnectedRegionCount`
   A 版里特别关键。它直接告诉你“纯空间切分有多不拓扑化”。

4. `allUnderThreshold`
   有没有超阈值

5. `allPlotsAssignedExactlyOnce`
   有没有漏分或重复分配

接下来如果你做 C 版或图划分版，再加两项：

6. 跨分区共边总长度
7. 分区紧凑度

---

## 七、你现在最该观察到的对比结论

做完 A 和 B，你应该能直接得到这几条经验。

第一条：

**KD 树很适合快速给出“空间上差不多”的初始答案。**

第二条：

**KD 树不理解真实邻接关系，所以天然不保证连通。**

第三条：

**一旦你要求拓扑正确，就必须把图斑邻接图引进来。**

第四条：

**“空间切分”和“拓扑连通”是两层不同问题。**

这四条吃透以后，你再去学图划分，很多抽象概念会瞬间落地。

---

## 八、你下一步最合理的演进路线

我建议你就按下面顺序走，不要跳。

### 第一步

先把上面这份代码跑起来。
先在规则网格上确认：

- A 版块数是否接近 `ceil(n / threshold)`
- B 版是否所有结果都连通
- 指标能不能稳定输出

### 第二步

把测试数据换成你自己更接近真实村地块的数据。
观察 A 和 B 的差异，尤其是：

- A 版有多少 `MultiPolygon`
- B 版块数增加了多少
- 哪些村界 / 地块分布下差异最大

### 第三步

在 B 版后面再加一个 C 版：

“连通块贪心合并”

也就是：

- 先把 B 版拆出来的连通块当原子块
- 再尝试把相邻的小块在不超过阈值的前提下合并
- 优先合并共边长大的、合并后更接近目标大小的块

这一步会是你从“空间基准”迈向“图划分思维”的关键桥梁。

---

## 九、你可以直接带走的学习笔记

这道题里，最值得内化的是下面几句。

**1. 切线不是结果，分组才是结果。**
只要图斑不可切，最终边界就必须来自图斑集合的并。

**2. KD 树解决的是“怎么先分得快”，不是“怎么分得最合理”。**

**3. 连通性不是空间盒子给的，而是邻接图给的。**

**4. 一个好基准，不一定最优，但必须足够简单，能把问题暴露出来。**

**5. 先做能解释的 A/B 基准，再做复杂优化，学习效率最高。**

如果你愿意，下一轮我就直接接着给你做 C 版设计：
“KD 树 + 连通拆分 + 贪心合并”的完整思路和代码框架。

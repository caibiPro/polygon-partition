package com.geo.split;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 方案B：基于邻接图的图划分方法
 *
 * 对比方案A（坐标递归二分），这个方案：
 * - 先建立图斑的邻接图
 * - 在图上进行递归二分（使用Fiedler向量思想的简化版，即坐标排序模拟谱方法）
 * - 最后从图划分结果恢复几何区域
 *
 * 优势：天然尊重图斑拓扑关系，切边数可控
 * 劣势：几何区域恢复较复杂，可能产生非连通区域
 */
public class GraphPartitionVillageSplitter {

    private final GeometryFactory gf;

    public GraphPartitionVillageSplitter() {
        this.gf = new GeometryFactory();
    }

    // ========================================================================
    // 邻接图数据结构
    // ========================================================================

    /**
     * 图斑邻接图
     * 
     * 为什么用邻接表而不是邻接矩阵：
     * - 图斑邻接图是稀疏图（每个图斑通常只和4-8个邻居相邻）
     * - 10万图斑的邻接矩阵需要 10^10 字节 → 不可行
     * - 邻接表只需要 O(N + E) 空间，E ≈ 4N ~ 8N
     */
    public static class AdjacencyGraph {
        /** 节点数（图斑数） */
        public final int nodeCount;
        /** adjacency[i] = 与节点i相邻的节点集合 */
        public final List<Set<Integer>> adjacency;
        /** 每个节点的质心坐标 */
        public final double[][] centroids;
        /** 边数 */
        public int edgeCount;

        public AdjacencyGraph(int n) {
            this.nodeCount = n;
            this.adjacency = new ArrayList<>(n);
            this.centroids = new double[n][2];
            for (int i = 0; i < n; i++) {
                adjacency.add(new HashSet<>());
            }
        }

        public void addEdge(int u, int v) {
            adjacency.get(u).add(v);
            adjacency.get(v).add(u);
            edgeCount++;
        }

        public int degree(int node) {
            return adjacency.get(node).size();
        }
    }

    // ========================================================================
    // 图构建
    // ========================================================================

    /**
     * 从图斑列表构建邻接图
     *
     * 步骤：
     * 1. 将所有图斑插入STR-tree空间索引
     * 2. 对每个图斑，用包围盒扩展查询潜在邻居
     * 3. 对候选邻居对，精确计算交集维度
     * 4. 交集维度≥1（线或面）→ 判定为相邻
     *
     * 为什么不直接两两比较：
     * - N=10万时，两两比较 = 50亿次，不可接受
     * - STR-tree将复杂度降到O(N log N + N * k)，k为平均邻居数
     */
    public AdjacencyGraph buildGraph(List<Geometry> parcels) {
        int n = parcels.size();
        AdjacencyGraph graph = new AdjacencyGraph(n);

        // 建立空间索引
        STRtree tree = new STRtree();
        for (int i = 0; i < n; i++) {
            tree.insert(parcels.get(i).getEnvelopeInternal(), i);
            Point centroid = parcels.get(i).getCentroid();
            graph.centroids[i][0] = centroid.getX();
            graph.centroids[i][1] = centroid.getY();
        }
        tree.build();

        // 查询邻接关系
        double tolerance = 1e-6;
        for (int i = 0; i < n; i++) {
            Envelope queryEnv = new Envelope(parcels.get(i).getEnvelopeInternal());
            queryEnv.expandBy(tolerance);

            @SuppressWarnings("unchecked")
            List<Integer> candidates = tree.query(queryEnv);

            for (int j : candidates) {
                if (j <= i) continue;
                try {
                    Geometry intersection = parcels.get(i).intersection(parcels.get(j));
                    if (intersection.getDimension() >= 1) {
                        graph.addEdge(i, j);
                    }
                } catch (Exception e) {
                    // skip topology exceptions
                }
            }
        }

        return graph;
    }

    // ========================================================================
    // 图划分：递归二分
    // ========================================================================

    /**
     * 划分结果
     */
    public static class Partition {
        /** 每个节点的分组编号 */
        public final int[] assignment;
        /** 分组数 */
        public final int groupCount;

        public Partition(int[] assignment, int groupCount) {
            this.assignment = assignment;
            this.groupCount = groupCount;
        }

        /** 获取某个分组的节点列表 */
        public List<Integer> getGroup(int groupId) {
            List<Integer> nodes = new ArrayList<>();
            for (int i = 0; i < assignment.length; i++) {
                if (assignment[i] == groupId) nodes.add(i);
            }
            return nodes;
        }
    }

    /**
     * 基于坐标的递归二分图划分
     *
     * 这是谱二分法（Spectral Bisection）的简化版本：
     * - 谱方法使用拉普拉斯矩阵的Fiedler向量来决定划分
     * - 坐标方法用质心坐标代替Fiedler向量
     * - 实践中效果相近，但实现简单得多
     *
     * 为什么坐标排序能近似谱方法：
     * 对于空间嵌入的图（如我们的地图图斑），空间坐标和Fiedler向量高度相关
     * 因为空间上接近的节点在图上也倾向于连通
     */
    public Partition partitionGraph(AdjacencyGraph graph, int threshold) {
        int[] assignment = new int[graph.nodeCount];
        Arrays.fill(assignment, 0);

        int[] groupCounter = {0};
        List<Integer> allNodes = new ArrayList<>();
        for (int i = 0; i < graph.nodeCount; i++) allNodes.add(i);

        recursiveBisectGraph(graph, allNodes, assignment, groupCounter, threshold);

        return new Partition(assignment, groupCounter[0]);
    }

    private void recursiveBisectGraph(AdjacencyGraph graph, List<Integer> nodes,
                                        int[] assignment, int[] groupCounter, int threshold) {
        if (nodes.size() <= threshold) {
            int gid = groupCounter[0]++;
            for (int node : nodes) {
                assignment[node] = gid;
            }
            return;
        }

        // 选择切割方向：计算节点质心坐标的方差，选方差大的方向
        double[] xCoords = nodes.stream().mapToDouble(n -> graph.centroids[n][0]).toArray();
        double[] yCoords = nodes.stream().mapToDouble(n -> graph.centroids[n][1]).toArray();

        double xVar = variance(xCoords);
        double yVar = variance(yCoords);
        boolean useX = xVar >= yVar;

        // 按选定方向的坐标排序
        nodes.sort((a, b) -> {
            double va = useX ? graph.centroids[a][0] : graph.centroids[a][1];
            double vb = useX ? graph.centroids[b][0] : graph.centroids[b][1];
            return Double.compare(va, vb);
        });

        // 从中间切分
        int mid = nodes.size() / 2;
        List<Integer> groupA = new ArrayList<>(nodes.subList(0, mid));
        List<Integer> groupB = new ArrayList<>(nodes.subList(mid, nodes.size()));

        // 可选：KL细化（Kernighan-Lin refinement）
        // 交换边界节点对来减少切边数
        klRefine(graph, groupA, groupB, 5);

        recursiveBisectGraph(graph, groupA, assignment, groupCounter, threshold);
        recursiveBisectGraph(graph, groupB, assignment, groupCounter, threshold);
    }

    /**
     * Kernighan-Lin局部细化
     *
     * KL算法的核心思想：
     * - 计算每个节点的"增益"：将其移到对面组后，切边数的减少量
     * - 贪心地交换增益最大的节点对
     * - 重复直到无法改善
     *
     * 为什么只做几轮：
     * - 完整KL的复杂度是O(N² log N)
     * - 在递归二分的每一层做完整KL太贵
     * - 少量轮次就能消除大部分不均衡
     */
    private void klRefine(AdjacencyGraph graph, List<Integer> groupA,
                           List<Integer> groupB, int maxIterations) {
        Set<Integer> setA = new HashSet<>(groupA);
        Set<Integer> setB = new HashSet<>(groupB);

        for (int iter = 0; iter < maxIterations; iter++) {
            boolean improved = false;

            // 找边界节点（至少有一个邻居在另一组）
            List<Integer> borderA = groupA.stream()
                .filter(n -> graph.adjacency.get(n).stream().anyMatch(setB::contains))
                .collect(Collectors.toList());
            List<Integer> borderB = groupB.stream()
                .filter(n -> graph.adjacency.get(n).stream().anyMatch(setA::contains))
                .collect(Collectors.toList());

            if (borderA.isEmpty() || borderB.isEmpty()) break;

            // 计算每个边界节点的外部边数 - 内部边数
            int bestGain = 0;
            int bestNodeA = -1, bestNodeB = -1;

            for (int a : borderA) {
                int externalA = (int) graph.adjacency.get(a).stream().filter(setB::contains).count();
                int internalA = (int) graph.adjacency.get(a).stream().filter(setA::contains).count();

                for (int b : borderB) {
                    int externalB = (int) graph.adjacency.get(b).stream().filter(setA::contains).count();
                    int internalB = (int) graph.adjacency.get(b).stream().filter(setB::contains).count();

                    // 交换a和b的增益
                    int gain = (externalA - internalA) + (externalB - internalB);
                    // 如果a和b相邻，交换后它们的连接不变，需要修正
                    if (graph.adjacency.get(a).contains(b)) {
                        gain -= 2;
                    }

                    if (gain > bestGain) {
                        bestGain = gain;
                        bestNodeA = a;
                        bestNodeB = b;
                    }
                }
            }

            if (bestGain > 0) {
                // 执行交换
                groupA.remove(Integer.valueOf(bestNodeA));
                groupB.remove(Integer.valueOf(bestNodeB));
                groupA.add(bestNodeB);
                groupB.add(bestNodeA);
                setA.remove(bestNodeA);
                setA.add(bestNodeB);
                setB.remove(bestNodeB);
                setB.add(bestNodeA);
                improved = true;
            }

            if (!improved) break;
        }
    }

    // ========================================================================
    // 几何区域恢复
    // ========================================================================

    /**
     * 从图划分结果恢复几何子区域
     *
     * 策略：
     * 1. 对每个分组，将其所有图斑做Union（合并）
     * 2. 未被图斑覆盖的区域（间隙），分配给最近的分组
     * 3. 用村边界裁剪每个分组的范围
     *
     * 为什么需要处理间隙：
     * 图斑通常不会完全覆盖村边界，中间有道路、水体等
     * 这些间隙必须被分配到某个子区域，否则切分后的子区域并集 ≠ 原村边界
     */
    public List<SpatialBisectionVillageSplitter.SplitRegion> recoverRegions(
            Geometry villageBoundary, List<Geometry> parcels, Partition partition) {

        List<SpatialBisectionVillageSplitter.SplitRegion> regions = new ArrayList<>();

        for (int gid = 0; gid < partition.groupCount; gid++) {
            List<Integer> nodeIndices = partition.getGroup(gid);
            if (nodeIndices.isEmpty()) continue;

            List<Geometry> groupParcels = nodeIndices.stream()
                .map(parcels::get)
                .collect(Collectors.toList());

            // 计算分组的"领地"：用所有质心的凸包 + 缓冲区
            List<Coordinate> centroids = groupParcels.stream()
                .map(p -> p.getCentroid().getCoordinate())
                .collect(Collectors.toList());

            // 使用Voronoi思想：计算每个分组的"势力范围"
            // 简化版：用凸包+适当缓冲作为区域，再与村边界求交
            Geometry groupUnion = CascadedPolygonUnion.union(groupParcels);
            Geometry regionEstimate = groupUnion.convexHull().buffer(
                Math.sqrt(villageBoundary.getArea() / partition.groupCount) * 0.5);
            Geometry region = villageBoundary.intersection(regionEstimate);

            regions.add(new SpatialBisectionVillageSplitter.SplitRegion(gid, region, groupParcels));
        }

        // 后处理：确保区域不重叠
        // （简化处理：对重叠部分，分配给图斑更多的区域）
        resolveOverlaps(regions);

        return regions;
    }

    /**
     * 解决区域重叠
     * 策略：按区域面积从大到小处理，后处理的区域从已有区域中挖去重叠部分
     */
    private void resolveOverlaps(List<SpatialBisectionVillageSplitter.SplitRegion> regions) {
        // 简化实现：暂不处理，留作扩展
        // 完整实现应使用Voronoi细分或权重距离分配
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private double variance(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0);
        return Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
    }

    /**
     * 计算划分的切边数（衡量划分质量的指标）
     */
    public int countCutEdges(AdjacencyGraph graph, Partition partition) {
        int cutEdges = 0;
        for (int i = 0; i < graph.nodeCount; i++) {
            for (int j : graph.adjacency.get(i)) {
                if (j > i && partition.assignment[i] != partition.assignment[j]) {
                    cutEdges++;
                }
            }
        }
        return cutEdges;
    }
}

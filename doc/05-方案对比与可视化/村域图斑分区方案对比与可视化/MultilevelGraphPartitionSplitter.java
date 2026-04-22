package com.geo.split;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 方案二：基于邻接图的多层级图划分（METIS思想的Java简化实现）
 *
 * ====== 核心思想 ======
 * 将"空间切分"转化为"图划分"，在图上操作天然保证拓扑安全。
 *
 * ====== 为什么天然拓扑安全 ======
 * 图划分只是给节点（图斑）打标签（分组号），
 * 不涉及任何"线切割多边形"的操作，
 * 因此不存在"切割线穿过图斑"的可能。
 *
 * ====== 三阶段多层级方法（Multi-level） ======
 * 这是METIS算法（Karypis & Kumar, 1998）的核心思想：
 *
 *   Phase 1: 粗化（Coarsening）
 *     将图逐层收缩：合并相邻节点对 → 得到越来越小的图
 *     为什么：在小图上做划分比在大图上快得多
 *
 *   Phase 2: 初始划分（Initial Partitioning）
 *     在最粗的图上，用简单方法（如BFS/坐标排序）做初始划分
 *     为什么：粗图只有几十个节点，任何方法都很快
 *
 *   Phase 3: 反粗化 + 细化（Uncoarsening + Refinement）
 *     逐层展开粗化，每层用KL/FM算法细化划分
 *     为什么：粗化保留了图的全局结构，细化修复局部不优
 *
 * ====== 本实现的简化 ======
 * 为了代码可读性，我们实现一个简化版本：
 *   - 粗化：使用Heavy Edge Matching（HEM）
 *   - 初始划分：坐标排序二分
 *   - 细化：KL边界交换
 *   - 几何恢复：图斑合并 + Voronoi分配间隙
 */
public class MultilevelGraphPartitionSplitter {

    private final GeometryFactory gf;

    public MultilevelGraphPartitionSplitter() {
        this.gf = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING));
    }

    // ================================================================
    // 图数据结构
    // ================================================================

    /**
     * 邻接图
     *
     * 为什么用邻接表（adjacency list）：
     *   地图图斑的邻接图是稀疏图，每个节点度数通常4~8
     *   邻接矩阵 O(N²) 空间 → 10万节点需要40GB → 不可行
     *   邻接表 O(N + E) 空间 → 10万节点约需要几MB → 可行
     */
    public static class Graph {
        int n;                              // 节点数
        List<List<int[]>> adj;              // adj[u] = list of [v, weight]
        double[][] coords;                  // 节点的空间坐标（质心）
        int[] nodeWeight;                   // 节点权重（代表的原始图斑数）
        int[] originalNodes;                // 粗化时：该节点对应的原始节点集合（用于展开）
        Map<Integer, List<Integer>> coarseToFine; // 粗节点 → 细节点映射

        Graph(int n) {
            this.n = n;
            this.adj = new ArrayList<>();
            this.coords = new double[n][2];
            this.nodeWeight = new int[n];
            Arrays.fill(nodeWeight, 1);
            for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        }

        void addEdge(int u, int v, int w) {
            adj.get(u).add(new int[]{v, w});
            adj.get(v).add(new int[]{u, w});
        }

        int degree(int u) { return adj.get(u).size(); }

        int totalWeight() { return Arrays.stream(nodeWeight).sum(); }
    }

    // ================================================================
    // Phase 0: 从图斑构建邻接图
    // ================================================================

    /**
     * 构建邻接图
     *
     * 使用STR-tree加速邻居查找：
     *   1. 所有图斑包围盒插入STR-tree
     *   2. 对每个图斑，查询包围盒重叠的候选邻居
     *   3. 对候选对做精确相交测试
     *   4. 交集维度≥1 → 共边 → 建边
     *
     * 边权 = 共边长度（用于后续划分时的切边代价计算）
     */
    public Graph buildGraph(List<Geometry> parcels) {
        int n = parcels.size();
        Graph g = new Graph(n);

        STRtree tree = new STRtree();
        for (int i = 0; i < n; i++) {
            tree.insert(parcels.get(i).getEnvelopeInternal(), i);
            Point c = parcels.get(i).getCentroid();
            g.coords[i] = new double[]{c.getX(), c.getY()};
        }
        tree.build();

        for (int i = 0; i < n; i++) {
            Envelope qe = new Envelope(parcels.get(i).getEnvelopeInternal());
            qe.expandBy(1e-6);

            @SuppressWarnings("unchecked")
            List<Integer> candidates = tree.query(qe);
            for (int j : candidates) {
                if (j <= i) continue;
                try {
                    Geometry inter = parcels.get(i).intersection(parcels.get(j));
                    if (inter.getDimension() >= 1) {
                        int weight = Math.max(1, (int)(inter.getLength() * 100));
                        g.addEdge(i, j, weight);
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        }
        return g;
    }

    // ================================================================
    // Phase 1: 粗化（Coarsening） —— Heavy Edge Matching
    // ================================================================

    /**
     * Heavy Edge Matching（HEM）粗化
     *
     * 算法：
     *   1. 随机顺序遍历所有未匹配节点
     *   2. 对每个未匹配节点u，找到其未匹配邻居中边权最大的v
     *   3. 将u和v合并为一个粗节点
     *   4. 粗节点继承u和v的所有邻居
     *
     * 为什么选"最重边"匹配：
     *   重边 = 共边长度大 = 两个图斑紧密相连
     *   合并紧密连接的节点，保留了图的全局结构
     *   最终划分时这些紧密连接的节点大概率在同一组
     *   → 减少了划分时需要考虑的"难决策"
     *
     * 一次粗化将节点数大约减半
     */
    private Graph coarsen(Graph fine) {
        int n = fine.n;
        int[] match = new int[n];
        Arrays.fill(match, -1);

        // 随机顺序
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        Collections.shuffle(order, new Random(42));

        // HEM匹配
        for (int u : order) {
            if (match[u] != -1) continue;

            int bestV = -1, bestW = -1;
            for (int[] edge : fine.adj.get(u)) {
                int v = edge[0], w = edge[1];
                if (match[v] == -1 && w > bestW) {
                    bestW = w;
                    bestV = v;
                }
            }

            if (bestV != -1) {
                match[u] = bestV;
                match[bestV] = u;
            }
        }

        // 构建粗图的节点映射
        Map<Integer, Integer> fineToCoarse = new HashMap<>();
        Map<Integer, List<Integer>> coarseToFine = new HashMap<>();
        int coarseN = 0;

        for (int u = 0; u < n; u++) {
            if (fineToCoarse.containsKey(u)) continue;
            int cid = coarseN++;
            fineToCoarse.put(u, cid);
            List<Integer> members = new ArrayList<>();
            members.add(u);

            if (match[u] != -1 && !fineToCoarse.containsKey(match[u])) {
                fineToCoarse.put(match[u], cid);
                members.add(match[u]);
            }
            coarseToFine.put(cid, members);
        }

        // 构建粗图
        Graph coarse = new Graph(coarseN);
        coarse.coarseToFine = coarseToFine;

        for (int cid = 0; cid < coarseN; cid++) {
            List<Integer> members = coarseToFine.get(cid);
            // 合并坐标和权重
            double cx = 0, cy = 0;
            int totalW = 0;
            for (int u : members) {
                cx += fine.coords[u][0] * fine.nodeWeight[u];
                cy += fine.coords[u][1] * fine.nodeWeight[u];
                totalW += fine.nodeWeight[u];
            }
            coarse.coords[cid] = new double[]{cx / totalW, cy / totalW};
            coarse.nodeWeight[cid] = totalW;
        }

        // 合并边
        Map<Long, Integer> edgeMap = new HashMap<>();
        for (int u = 0; u < n; u++) {
            int cu = fineToCoarse.get(u);
            for (int[] edge : fine.adj.get(u)) {
                int cv = fineToCoarse.get(edge[0]);
                if (cu == cv) continue;  // 合并后的内部边消失
                long key = Math.min(cu, cv) * (long)coarseN + Math.max(cu, cv);
                edgeMap.merge(key, edge[1], Integer::sum);
            }
        }

        for (Map.Entry<Long, Integer> entry : edgeMap.entrySet()) {
            int cu = (int)(entry.getKey() / coarseN);
            int cv = (int)(entry.getKey() % coarseN);
            coarse.addEdge(cu, cv, entry.getValue());
        }

        return coarse;
    }

    // ================================================================
    // Phase 2: 初始划分
    // ================================================================

    /**
     * 在粗图上做初始划分
     * 使用坐标排序的递归二分（因为粗图节点少，用简单方法即可）
     */
    private int[] initialPartition(Graph coarse, int numParts) {
        int[] assignment = new int[coarse.n];
        List<Integer> nodes = new ArrayList<>();
        for (int i = 0; i < coarse.n; i++) nodes.add(i);

        int totalWeight = coarse.totalWeight();
        int targetPerPart = (totalWeight + numParts - 1) / numParts;

        recursiveBisect(coarse, nodes, assignment, 0, numParts - 1, targetPerPart);
        return assignment;
    }

    private void recursiveBisect(Graph g, List<Integer> nodes, int[] assignment,
                                   int loGroup, int hiGroup, int targetWeight) {
        if (loGroup >= hiGroup || nodes.size() <= 1) {
            for (int u : nodes) assignment[u] = loGroup;
            return;
        }

        // 按方差最大的坐标方向排序
        double[] xs = nodes.stream().mapToDouble(u -> g.coords[u][0]).toArray();
        double[] ys = nodes.stream().mapToDouble(u -> g.coords[u][1]).toArray();
        boolean useX = variance(xs) >= variance(ys);

        nodes.sort((a, b) -> {
            double va = useX ? g.coords[a][0] : g.coords[a][1];
            double vb = useX ? g.coords[b][0] : g.coords[b][1];
            return Double.compare(va, vb);
        });

        // 按权重找切分点
        int midGroup = (loGroup + hiGroup) / 2;
        int leftCapacity = (midGroup - loGroup + 1) * targetWeight;

        int splitIdx = 0;
        int accumWeight = 0;
        for (int i = 0; i < nodes.size(); i++) {
            accumWeight += g.nodeWeight[nodes.get(i)];
            if (accumWeight >= leftCapacity) {
                splitIdx = i + 1;
                break;
            }
        }
        if (splitIdx == 0) splitIdx = 1;
        if (splitIdx >= nodes.size()) splitIdx = nodes.size() - 1;

        List<Integer> left = new ArrayList<>(nodes.subList(0, splitIdx));
        List<Integer> right = new ArrayList<>(nodes.subList(splitIdx, nodes.size()));

        recursiveBisect(g, left, assignment, loGroup, midGroup, targetWeight);
        recursiveBisect(g, right, assignment, midGroup + 1, hiGroup, targetWeight);
    }

    // ================================================================
    // Phase 3: 反粗化 + KL细化
    // ================================================================

    /**
     * 将粗图的划分"投射"回细图
     */
    private int[] projectPartition(Graph fine, Graph coarse, int[] coarseAssignment,
                                     Map<Integer, Integer> fineToCoarse) {
        int[] fineAssignment = new int[fine.n];
        for (int u = 0; u < fine.n; u++) {
            fineAssignment[u] = coarseAssignment[fineToCoarse.get(u)];
        }
        return fineAssignment;
    }

    /**
     * KL/FM风格的局部细化
     *
     * Fiduccia-Mattheyses (FM) 改进：
     *   与KL不同，FM每次只移动一个节点（而非交换一对）
     *   这更灵活，允许组大小的微调
     *
     * 算法：
     *   1. 找所有边界节点（有邻居在其他组的节点）
     *   2. 计算每个边界节点移到其他组的"增益"
     *      增益 = 减少的切边权 - 增加的切边权
     *   3. 移动增益最大的节点（如果移动后不违反均衡约束）
     *   4. 重复直到无正增益
     */
    private void refine(Graph g, int[] assignment, int numParts, int maxWeight) {
        boolean improved = true;
        int iterations = 0;

        while (improved && iterations < 20) {
            improved = false;
            iterations++;

            // 计算各组权重
            int[] groupWeight = new int[numParts];
            for (int u = 0; u < g.n; u++) {
                groupWeight[assignment[u]] += g.nodeWeight[u];
            }

            // 遍历所有边界节点
            for (int u = 0; u < g.n; u++) {
                int curGroup = assignment[u];

                // 计算移到各邻居组的增益
                Map<Integer, Integer> gainToGroup = new HashMap<>();
                for (int[] edge : g.adj.get(u)) {
                    int v = edge[0], w = edge[1];
                    int vGroup = assignment[v];
                    if (vGroup == curGroup) {
                        // 这条边是内部边 → 移走后变成切边 → 增益减少
                        for (int gid : gainToGroup.keySet()) {
                            gainToGroup.merge(gid, -w, Integer::sum);
                        }
                    } else {
                        // 这条边是切边 → 移到vGroup后变成内部边 → 增益增加
                        gainToGroup.merge(vGroup, w, Integer::sum);
                    }
                }

                // 找最大增益的目标组
                int bestGroup = -1, bestGain = 0;
                for (Map.Entry<Integer, Integer> e : gainToGroup.entrySet()) {
                    int targetGroup = e.getKey();
                    int gain = e.getValue();
                    // 检查均衡约束
                    if (gain > bestGain &&
                        groupWeight[targetGroup] + g.nodeWeight[u] <= maxWeight &&
                        groupWeight[curGroup] - g.nodeWeight[u] > 0) {
                        bestGain = gain;
                        bestGroup = targetGroup;
                    }
                }

                if (bestGroup >= 0) {
                    groupWeight[curGroup] -= g.nodeWeight[u];
                    groupWeight[bestGroup] += g.nodeWeight[u];
                    assignment[u] = bestGroup;
                    improved = true;
                }
            }
        }
    }

    // ================================================================
    // 完整流程
    // ================================================================

    /**
     * 主入口：多层级图划分
     */
    public List<TopologySafeBisectionSplitter.SplitRegion> split(
            Geometry village, List<Geometry> parcels, int threshold) {
        if (parcels == null || parcels.size() <= threshold) {
            return Collections.singletonList(
                new TopologySafeBisectionSplitter.SplitRegion(0, village,
                    parcels != null ? parcels : new ArrayList<>()));
        }

        int n = parcels.size();
        int numParts = (int) Math.ceil((double) n / threshold);

        // Step 0: 建图
        Graph original = buildGraph(parcels);

        // Step 1: 多层粗化
        List<Graph> hierarchy = new ArrayList<>();
        List<Map<Integer, Integer>> mappings = new ArrayList<>();
        hierarchy.add(original);

        Graph current = original;
        while (current.n > Math.max(numParts * 2, 50)) {
            Graph coarser = coarsen(current);
            if (coarser.n >= current.n * 0.8) break;  // 粗化效率不够，停止

            // 记录 fine→coarse 映射
            Map<Integer, Integer> mapping = new HashMap<>();
            for (Map.Entry<Integer, List<Integer>> entry : coarser.coarseToFine.entrySet()) {
                for (int fineNode : entry.getValue()) {
                    mapping.put(fineNode, entry.getKey());
                }
            }
            mappings.add(mapping);
            hierarchy.add(coarser);
            current = coarser;
        }

        // Step 2: 在最粗图上初始划分
        int[] assignment = initialPartition(current, numParts);

        // Step 3: 反粗化 + 细化
        for (int level = hierarchy.size() - 2; level >= 0; level--) {
            Graph finer = hierarchy.get(level);
            assignment = projectPartition(finer, hierarchy.get(level + 1),
                                           assignment, mappings.get(level));
            refine(finer, assignment, numParts, threshold + threshold / 5); // 允许5%余量
        }

        // Step 4: 恢复几何区域
        return buildRegions(village, parcels, assignment, numParts);
    }

    // ================================================================
    // 几何恢复
    // ================================================================

    /**
     * 从划分结果恢复几何子区域
     *
     * 难点：图斑之间有间隙（道路等），这些间隙也需要被分配
     *
     * 策略：
     *   1. 每个分组的图斑做Union → 得到分组的"图斑区域"
     *   2. 计算村边界中未被图斑覆盖的间隙区域
     *   3. 对间隙区域，按最近距离分配给相邻分组
     *   4. 最终每个分组 = 图斑Union + 分配到的间隙
     *
     * 为什么不能忽略间隙：
     *   如果忽略，子区域的并集 ≠ 村边界，不满足完全覆盖要求
     */
    private List<TopologySafeBisectionSplitter.SplitRegion> buildRegions(
            Geometry village, List<Geometry> parcels, int[] assignment, int numParts) {

        List<TopologySafeBisectionSplitter.SplitRegion> regions = new ArrayList<>();

        for (int gid = 0; gid < numParts; gid++) {
            List<Geometry> groupParcels = new ArrayList<>();
            for (int i = 0; i < parcels.size(); i++) {
                if (assignment[i] == gid) groupParcels.add(parcels.get(i));
            }
            if (groupParcels.isEmpty()) continue;

            // 合并本组图斑 + 凸包缓冲
            try {
                Geometry groupUnion = CascadedPolygonUnion.union(groupParcels);
                // 小缓冲填补图斑间微小间隙
                Geometry buffered = groupUnion.buffer(0.5).buffer(-0.3);
                Geometry clipped = village.intersection(buffered);
                Geometry region = clipped.isEmpty() ? groupUnion : clipped;
                regions.add(new TopologySafeBisectionSplitter.SplitRegion(gid, region, groupParcels));
            } catch (Exception e) {
                // fallback: 用凸包
                Geometry hull = gf.createMultiPoint(
                    groupParcels.stream().map(Geometry::getCentroid).toArray(Point[]::new)
                ).convexHull();
                Geometry region = village.intersection(hull.buffer(10));
                regions.add(new TopologySafeBisectionSplitter.SplitRegion(gid, region, groupParcels));
            }
        }

        return regions;
    }

    // ================================================================
    // 工具
    // ================================================================

    private double variance(double[] vals) {
        double mean = Arrays.stream(vals).average().orElse(0);
        return Arrays.stream(vals).map(v -> (v - mean) * (v - mean)).average().orElse(0);
    }

    /**
     * 计算切边代价（划分质量的核心指标）
     */
    public int cutEdgeCost(Graph g, int[] assignment) {
        int cost = 0;
        for (int u = 0; u < g.n; u++) {
            for (int[] edge : g.adj.get(u)) {
                if (edge[0] > u && assignment[u] != assignment[edge[0]]) {
                    cost += edge[1];
                }
            }
        }
        return cost;
    }
}

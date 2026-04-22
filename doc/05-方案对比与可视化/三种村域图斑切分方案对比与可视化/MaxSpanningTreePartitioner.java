package com.geo.split;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 方案三：邻接图 + 最大生成树 + 递归平衡切树
 *
 * =====================================================================
 *  为什么这个方案可能是最优雅的？
 * =====================================================================
 *
 *  1. 拓扑安全 — 天然保证（只给节点打标签，不做几何切割）
 *  2. 连通性   — 天然保证（树删边后的两个分量一定是连通的）
 *  3. 均衡性   — 精确控制（可以精确计算子树大小）
 *  4. 切割语义 — 明确（切断树上权重最小的边 = 从最弱连接处分开）
 *  5. 实现简洁 — 核心只需 并查集 + DFS/BFS
 *
 * =====================================================================
 *  三步流程
 * =====================================================================
 *
 *  Step 1: 构建邻接图
 *    图斑 → 节点, 共边 → 加权边（权重 = 共边长度）
 *    使用STR-tree加速邻居查找 → O(N log N)
 *
 *  Step 2: 提取最大生成树（Maximum Spanning Tree）
 *    Kruskal算法 + 并查集: 边按权重从大到小排序，贪心选边
 *    为什么是"最大"而不是"最小"？
 *      → 最大生成树保留了图中最强（共边最长）的连接
 *      → 切割时删掉的是树上权重最小的边
 *      → 即从图斑之间连接最弱的地方切开
 *      → 这恰好对应了地理上最自然的分界线
 *
 *  Step 3: 递归平衡切树
 *    树有一个完美的性质：删除任意一条边 → 恰好裂成两个连通分量
 *    递归地找到"最均衡"的切割边，删除它，然后对子树继续切割
 *    均衡 = 两侧子树的节点权重之差最小
 *    同等均衡度下，选权重最小的边（保留更强的连接）
 */
public class MaxSpanningTreePartitioner {

    private final GeometryFactory gf;

    /** 判断共边的容差 */
    private static final double TOLERANCE = 1e-6;

    public MaxSpanningTreePartitioner() {
        this.gf = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING));
    }

    // ================================================================
    // 数据结构
    // ================================================================

    /** 加权边 */
    static class Edge implements Comparable<Edge> {
        final int u, v;
        final double weight; // 共边长度

        Edge(int u, int v, double weight) {
            this.u = u; this.v = v; this.weight = weight;
        }

        /** 按权重降序排列（最大生成树需要从大到小） */
        @Override
        public int compareTo(Edge o) {
            return Double.compare(o.weight, this.weight); // 注意：降序
        }
    }

    /**
     * 并查集（Union-Find / Disjoint Set Union）
     *
     * 为什么用并查集：
     *   Kruskal算法的核心操作是"判断两个节点是否已连通"
     *   并查集能在近O(1)时间内完成这个判断（路径压缩+按秩合并）
     *
     * 为什么不用BFS/DFS判断连通性：
     *   每次判断需要O(N)时间
     *   Kruskal需要判断O(E)次 → 总共O(NE) → 太慢
     *   并查集只需O(Eα(N)) ≈ O(E) → 几乎线性
     */
    static class UnionFind {
        int[] parent, rank;
        int components;

        UnionFind(int n) {
            parent = new int[n];
            rank = new int[n];
            components = n;
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        /** 查找根（带路径压缩） */
        int find(int x) {
            if (parent[x] != x) parent[x] = find(parent[x]);
            return parent[x];
        }

        /** 合并两个集合（按秩合并） */
        boolean union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return false; // 已经在同一集合
            if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
            parent[rb] = ra;
            if (rank[ra] == rank[rb]) rank[ra]++;
            components--;
            return true;
        }

        boolean connected(int a, int b) {
            return find(a) == find(b);
        }
    }

    /** 树的邻接表表示 */
    static class Tree {
        int n;
        List<List<int[]>> adj; // adj[u] = list of [v, edgeIndex]
        List<Edge> edges;
        int[] subtreeSize; // 以0为根时各节点的子树大小

        Tree(int n) {
            this.n = n;
            this.adj = new ArrayList<>();
            this.edges = new ArrayList<>();
            for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        }

        void addEdge(Edge e) {
            int idx = edges.size();
            edges.add(e);
            adj.get(e.u).add(new int[]{e.v, idx});
            adj.get(e.v).add(new int[]{e.u, idx});
        }

        /** 计算以root为根的各子树大小 */
        void computeSubtreeSizes(int root) {
            subtreeSize = new int[n];
            Arrays.fill(subtreeSize, 1);
            boolean[] visited = new boolean[n];
            // 后序遍历（迭代版，避免栈溢出）
            Deque<int[]> stack = new ArrayDeque<>(); // [node, parent]
            List<int[]> order = new ArrayList<>();
            stack.push(new int[]{root, -1});
            while (!stack.isEmpty()) {
                int[] cur = stack.pop();
                order.add(cur);
                visited[cur[0]] = true;
                for (int[] nb : adj.get(cur[0])) {
                    if (!visited[nb[0]]) {
                        stack.push(new int[]{nb[0], cur[0]});
                    }
                }
            }
            // 逆序累加（后序）
            for (int i = order.size() - 1; i >= 0; i--) {
                int node = order.get(i)[0], par = order.get(i)[1];
                if (par >= 0) {
                    subtreeSize[par] += subtreeSize[node];
                }
            }
        }
    }

    /** 切分结果 */
    public static class SplitRegion {
        public final int id;
        public final Geometry boundary;
        public final List<Geometry> parcels;
        public final List<Integer> parcelIndices; // 原始图斑索引

        public SplitRegion(int id, Geometry boundary, List<Geometry> parcels, List<Integer> indices) {
            this.id = id;
            this.boundary = boundary;
            this.parcels = parcels;
            this.parcelIndices = indices;
        }
    }

    // ================================================================
    // Step 1: 构建邻接图
    // ================================================================

    /**
     * 构建图斑邻接图的所有加权边
     *
     * 算法：
     *   1. 所有图斑包围盒 → STR-tree
     *   2. 对每个图斑，查询包围盒重叠的候选邻居
     *   3. 对候选对做精确intersection
     *   4. intersection维度≥1 → 共边 → 权重=共边长度
     *
     * 时间: O(N log N) 建索引 + O(N × k × T_intersection) 查询
     *   k = 平均候选邻居数（通常8~12），T_intersection = 单次相交计算时间
     * 空间: O(N + E)
     */
    public List<Edge> buildAdjacencyEdges(List<Geometry> parcels) {
        int n = parcels.size();
        STRtree tree = new STRtree();
        for (int i = 0; i < n; i++) {
            tree.insert(parcels.get(i).getEnvelopeInternal(), i);
        }
        tree.build();

        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Envelope qe = new Envelope(parcels.get(i).getEnvelopeInternal());
            qe.expandBy(TOLERANCE);

            @SuppressWarnings("unchecked")
            List<Integer> candidates = tree.query(qe);
            for (int j : candidates) {
                if (j <= i) continue;
                try {
                    Geometry inter = parcels.get(i).intersection(parcels.get(j));
                    // 维度≥1意味着交集包含线段（不仅是点）
                    if (inter.getDimension() >= 1) {
                        double sharedLength = inter.getLength();
                        if (sharedLength > TOLERANCE) {
                            edges.add(new Edge(i, j, sharedLength));
                        }
                    }
                } catch (TopologyException e) {
                    // JTS在某些边界情况下可能抛出拓扑异常
                    // 使用buffer(0)修复后重试
                    try {
                        Geometry pi = parcels.get(i).buffer(0);
                        Geometry pj = parcels.get(j).buffer(0);
                        Geometry inter = pi.intersection(pj);
                        if (inter.getDimension() >= 1 && inter.getLength() > TOLERANCE) {
                            edges.add(new Edge(i, j, inter.getLength()));
                        }
                    } catch (Exception e2) {
                        // 仍然失败，跳过这对
                    }
                }
            }
        }
        return edges;
    }

    // ================================================================
    // Step 2: 最大生成树（Kruskal + 并查集）
    // ================================================================

    /**
     * Kruskal算法求最大生成树
     *
     * 为什么用Kruskal而不是Prim：
     *   - Kruskal对稀疏图更高效：O(E log E)
     *   - 我们的邻接图是稀疏图（E ≈ 4N ~ 8N）
     *   - Prim + 斐波那契堆虽然理论上O(E + N log N)更优
     *     但实现复杂度高，且对稀疏图实际差异不大
     *
     * 最大生成树 vs 最小生成树：
     *   唯一区别：排序方向（降序 vs 升序）
     *   概念上：
     *     最小ST → 保留最弱连接 → 切割时断的是强连接 → 不好
     *     最大ST → 保留最强连接 → 切割时断的是弱连接 → 从自然缝隙切开
     *
     * @return 最大生成树（N-1条边），如果图不连通则返回最大生成森林
     */
    public Tree buildMaxSpanningTree(int nodeCount, List<Edge> edges) {
        // 按权重降序排列
        List<Edge> sorted = new ArrayList<>(edges);
        Collections.sort(sorted); // Edge.compareTo是降序

        UnionFind uf = new UnionFind(nodeCount);
        Tree tree = new Tree(nodeCount);

        for (Edge e : sorted) {
            if (uf.union(e.u, e.v)) {
                tree.addEdge(e);
                if (tree.edges.size() == nodeCount - 1) break; // 树完成
            }
        }

        // 处理不连通的情况（某些图斑可能孤立）
        if (uf.components > 1) {
            System.out.println("WARNING: Graph has " + uf.components +
                " connected components. Some parcels may be isolated.");
        }

        return tree;
    }

    // ================================================================
    // Step 3: 递归平衡切树
    // ================================================================

    /**
     * 递归平衡切树的核心算法
     *
     * 为什么树的切割如此优雅：
     *   图的一般划分是NP-hard问题
     *   但树的划分可以在O(N)时间内精确求解！
     *   因为树删除一条边后恰好形成两个连通分量
     *   且通过子树大小可以精确知道两侧各有多少节点
     *
     * 切割边的选择策略：
     *   对每条树边，计算删除后两侧的节点数（或权重和）
     *   选择使两侧最均衡的边
     *   若均衡度相同，选权重最小的边（切断最弱的连接）
     *
     * 时间复杂度：
     *   单次切割：O(N)（DFS计算子树大小 + 遍历所有边）
     *   递归深度：O(log(N/K))
     *   总计：O(N log(N/K))
     */
    public List<SplitRegion> recursiveBalancedCut(Tree tree, List<Geometry> parcels,
                                                    Geometry village, int threshold) {
        List<SplitRegion> results = new ArrayList<>();
        int[] idGen = {0};

        // 初始：所有节点在一组
        Set<Integer> allNodes = new HashSet<>();
        for (int i = 0; i < tree.n; i++) allNodes.add(i);

        // 树边的活跃状态（被切断的边标记为inactive）
        boolean[] edgeActive = new boolean[tree.edges.size()];
        Arrays.fill(edgeActive, true);

        recursiveCut(tree, allNodes, edgeActive, parcels, village, threshold, results, idGen, 0);

        return results;
    }

    private void recursiveCut(Tree tree, Set<Integer> nodeSet, boolean[] edgeActive,
                               List<Geometry> parcels, Geometry village, int threshold,
                               List<SplitRegion> results, int[] idGen, int depth) {
        // 终止条件
        if (nodeSet.size() <= threshold || depth > 50) {
            results.add(buildRegion(idGen[0]++, nodeSet, parcels, village));
            return;
        }

        // 收集当前子树的活跃边
        List<Integer> activeEdgeIndices = new ArrayList<>();
        for (int i = 0; i < tree.edges.size(); i++) {
            if (!edgeActive[i]) continue;
            Edge e = tree.edges.get(i);
            if (nodeSet.contains(e.u) && nodeSet.contains(e.v)) {
                activeEdgeIndices.add(i);
            }
        }

        if (activeEdgeIndices.isEmpty()) {
            // 没有可切的边（孤立节点组）
            results.add(buildRegion(idGen[0]++, nodeSet, parcels, village));
            return;
        }

        // 在当前子树上计算子树大小
        // 选一个根节点（任取nodeSet中的一个）
        int root = nodeSet.iterator().next();
        Map<Integer, Integer> subtreeSize = computeLocalSubtreeSize(
            tree, nodeSet, edgeActive, root);

        int totalSize = nodeSet.size();

        // 寻找最均衡的切割边
        int bestEdgeIdx = -1;
        int bestImbalance = Integer.MAX_VALUE;
        double bestEdgeWeight = Double.MAX_VALUE;

        for (int idx : activeEdgeIndices) {
            Edge e = tree.edges.get(idx);

            // 删除这条边后，以e.u为根的子树（不经过e.v方向的部分）
            // 和以e.v为根的子树
            // 利用子树大小：在以root为根的树中，
            // 删除边(u,v)后，如果v是u的子节点，则v侧有subtreeSize[v]个节点
            // u侧有 totalSize - subtreeSize[v] 个节点

            int childSide; // 远离root那侧的大小
            // 判断谁是谁的子节点
            if (subtreeSize.containsKey(e.v) && isDescendant(tree, edgeActive, nodeSet, root, e.u, e.v)) {
                childSide = subtreeSize.get(e.v);
            } else if (subtreeSize.containsKey(e.u)) {
                childSide = subtreeSize.get(e.u);
            } else {
                continue;
            }

            int otherSide = totalSize - childSide;
            int imbalance = Math.abs(childSide - otherSide);

            // 选最均衡的；若相同，选权重最小的边（切断最弱连接）
            if (imbalance < bestImbalance ||
                (imbalance == bestImbalance && e.weight < bestEdgeWeight)) {
                bestImbalance = imbalance;
                bestEdgeWeight = e.weight;
                bestEdgeIdx = idx;
            }
        }

        if (bestEdgeIdx < 0) {
            results.add(buildRegion(idGen[0]++, nodeSet, parcels, village));
            return;
        }

        // 执行切割
        edgeActive[bestEdgeIdx] = false;

        // BFS分出两个连通分量
        Edge cutEdge = tree.edges.get(bestEdgeIdx);
        Set<Integer> compA = bfsComponent(tree, cutEdge.u, nodeSet, edgeActive);
        Set<Integer> compB = new HashSet<>(nodeSet);
        compB.removeAll(compA);

        // 递归处理
        recursiveCut(tree, compA, edgeActive, parcels, village, threshold, results, idGen, depth + 1);
        recursiveCut(tree, compB, edgeActive, parcels, village, threshold, results, idGen, depth + 1);

        // 恢复边状态（回溯），便于其他分支使用
        // 实际上不需要恢复，因为compA和compB不重叠
    }

    /**
     * 在当前活跃子树上计算子树大小
     */
    private Map<Integer, Integer> computeLocalSubtreeSize(
            Tree tree, Set<Integer> nodeSet, boolean[] edgeActive, int root) {
        Map<Integer, Integer> size = new HashMap<>();
        for (int n : nodeSet) size.put(n, 1);

        // BFS后序遍历
        List<int[]> order = new ArrayList<>(); // [node, parent]
        Set<Integer> visited = new HashSet<>();
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{root, -1});
        while (!stack.isEmpty()) {
            int[] cur = stack.pop();
            if (visited.contains(cur[0])) continue;
            visited.add(cur[0]);
            order.add(cur);
            for (int[] nb : tree.adj.get(cur[0])) {
                int v = nb[0], eIdx = nb[1];
                if (!visited.contains(v) && edgeActive[eIdx] && nodeSet.contains(v)) {
                    stack.push(new int[]{v, cur[0]});
                }
            }
        }

        // 逆序累加
        for (int i = order.size() - 1; i >= 0; i--) {
            int node = order.get(i)[0], par = order.get(i)[1];
            if (par >= 0 && size.containsKey(par)) {
                size.put(par, size.get(par) + size.get(node));
            }
        }
        return size;
    }

    /**
     * 判断target是否是source的子孙（在当前活跃树中）
     */
    private boolean isDescendant(Tree tree, boolean[] edgeActive, Set<Integer> nodeSet,
                                   int root, int source, int target) {
        // 从root做BFS/DFS，看target是否在source的子树中
        // 简化：直接从source出发BFS，不经过root方向，看能否到达target
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        visited.add(source);

        // 找source的父节点（从root到source路径上的前一个）
        // 然后从source向子节点方向BFS
        // 更简单的方法：直接BFS整个子树看target在不在
        // 但我们需要知道source在树中的"上方"是什么

        // 最简实现：从target开始BFS，只走活跃边，不经过source
        // 如果能到达root → target不在source的子树中 → source是target的子节点
        Set<Integer> fromTarget = new HashSet<>();
        Deque<Integer> q2 = new ArrayDeque<>();
        q2.add(target); fromTarget.add(target);
        while (!q2.isEmpty()) {
            int u = q2.poll();
            if (u == root) return true; // root可达 → target在source的子树中（因为我们没经过source到target的边）
            for (int[] nb : tree.adj.get(u)) {
                int v = nb[0]; int eIdx = nb[1];
                if (!fromTarget.contains(v) && edgeActive[eIdx] && nodeSet.contains(v) && v != source) {
                    fromTarget.add(v);
                    q2.add(v);
                }
            }
        }
        // 如果从target出发不经过source无法到达root
        // 说明target在source到root的路径上被source隔开了
        // 即target是source的子节点
        return !fromTarget.contains(root);
    }

    /**
     * 从起点BFS，只走活跃边，返回连通分量
     */
    private Set<Integer> bfsComponent(Tree tree, int start,
                                        Set<Integer> nodeSet, boolean[] edgeActive) {
        Set<Integer> comp = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        comp.add(start);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (int[] nb : tree.adj.get(u)) {
                int v = nb[0], eIdx = nb[1];
                if (!comp.contains(v) && edgeActive[eIdx] && nodeSet.contains(v)) {
                    comp.add(v);
                    queue.add(v);
                }
            }
        }
        return comp;
    }

    // ================================================================
    // 几何恢复
    // ================================================================

    /**
     * 从节点集合构建子区域的几何边界
     *
     * 策略：将本组所有图斑做Union，然后与村边界求交
     *
     * 为什么直接Union就够了（相比METIS方案更简单）：
     *   因为树切割保证了连通性，所以同组图斑必定在空间上连片
     *   Union的结果不会出现多个不连通的碎片
     *   这是树方案相比一般图划分的巨大优势
     */
    private SplitRegion buildRegion(int id, Set<Integer> nodeSet,
                                      List<Geometry> parcels, Geometry village) {
        List<Integer> indices = new ArrayList<>(nodeSet);
        List<Geometry> groupParcels = indices.stream()
            .map(parcels::get)
            .collect(Collectors.toList());

        try {
            // Union所有图斑
            Geometry groupUnion = CascadedPolygonUnion.union(groupParcels);
            // 小缓冲填补图斑间的微小间隙（道路等）
            Geometry buffered = groupUnion.buffer(
                Math.sqrt(groupUnion.getArea() / groupParcels.size()) * 0.3);
            // 与村边界求交，确保不超出
            Geometry clipped = village.intersection(buffered);
            Geometry region = (clipped != null && !clipped.isEmpty()) ? clipped : groupUnion;

            return new SplitRegion(id, region, groupParcels, indices);
        } catch (Exception e) {
            // fallback: 用凸包
            Geometry hull = gf.createMultiPoint(
                groupParcels.stream()
                    .map(p -> p.getCentroid().getCoordinate())
                    .toArray(Coordinate[]::new)
            ).convexHull().buffer(1);
            Geometry region = village.intersection(hull);
            return new SplitRegion(id, region, groupParcels, indices);
        }
    }

    // ================================================================
    // 完整流程（主入口）
    // ================================================================

    /**
     * 一站式切分入口
     *
     * @param village   村边界多边形
     * @param parcels   图斑列表
     * @param threshold 每个子区域最大图斑数
     * @return 切分后的子区域列表
     */
    public List<SplitRegion> split(Geometry village, List<Geometry> parcels, int threshold) {
        if (parcels == null || parcels.size() <= threshold) {
            List<Integer> allIdx = new ArrayList<>();
            for (int i = 0; i < (parcels != null ? parcels.size() : 0); i++) allIdx.add(i);
            return Collections.singletonList(new SplitRegion(0, village,
                parcels != null ? parcels : new ArrayList<>(), allIdx));
        }

        System.out.printf("[MaxST] Splitting %d parcels with threshold %d%n", parcels.size(), threshold);

        // Step 1: 建邻接图
        long t0 = System.currentTimeMillis();
        List<Edge> edges = buildAdjacencyEdges(parcels);
        long t1 = System.currentTimeMillis();
        System.out.printf("[MaxST] Step1 - Built adjacency: %d edges in %d ms%n", edges.size(), t1-t0);

        // Step 2: 最大生成树
        Tree mst = buildMaxSpanningTree(parcels.size(), edges);
        long t2 = System.currentTimeMillis();
        System.out.printf("[MaxST] Step2 - Max spanning tree: %d tree edges in %d ms%n",
            mst.edges.size(), t2-t1);

        // Step 3: 递归平衡切树
        List<SplitRegion> regions = recursiveBalancedCut(mst, parcels, village, threshold);
        long t3 = System.currentTimeMillis();
        System.out.printf("[MaxST] Step3 - Recursive cut: %d regions in %d ms%n",
            regions.size(), t3-t2);
        System.out.printf("[MaxST] Total: %d ms%n", t3-t0);

        return regions;
    }

    // ================================================================
    // 评估指标
    // ================================================================

    /** 均衡度: max_count / avg_count */
    public static double balanceFactor(List<SplitRegion> regions) {
        int[] counts = regions.stream().mapToInt(r -> r.parcels.size()).toArray();
        double avg = Arrays.stream(counts).average().orElse(1);
        int max = Arrays.stream(counts).max().orElse(0);
        return max / avg;
    }

    /** 平均紧凑度（Polsby-Popper） */
    public static double avgCompactness(List<SplitRegion> regions) {
        return regions.stream()
            .mapToDouble(r -> {
                double a = r.boundary.getArea();
                double p = r.boundary.getLength();
                return p > 0 ? 4 * Math.PI * a / (p * p) : 0;
            })
            .average().orElse(0);
    }

    /**
     * 验证切分结果
     */
    public List<String> validate(Geometry village, List<Geometry> parcels,
                                  List<SplitRegion> regions, int threshold) {
        List<String> issues = new ArrayList<>();

        // 阈值检查
        for (SplitRegion r : regions) {
            if (r.parcels.size() > threshold)
                issues.add("ERROR: Region#" + r.id + " has " + r.parcels.size() + " > " + threshold);
        }

        // 总数检查
        int total = regions.stream().mapToInt(r -> r.parcels.size()).sum();
        if (total != parcels.size())
            issues.add("ERROR: Total " + total + " != " + parcels.size());

        // 连通性检查（树方案的核心优势）
        // 每个区域内的图斑应该通过邻接关系连通
        List<Edge> allEdges = buildAdjacencyEdges(parcels);
        for (SplitRegion r : regions) {
            if (r.parcelIndices.size() <= 1) continue;
            Set<Integer> idxSet = new HashSet<>(r.parcelIndices);
            // BFS检查连通性
            Set<Integer> visited = new HashSet<>();
            Deque<Integer> queue = new ArrayDeque<>();
            int start = r.parcelIndices.get(0);
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                int u = queue.poll();
                for (Edge e : allEdges) {
                    int nb = -1;
                    if (e.u == u && idxSet.contains(e.v)) nb = e.v;
                    if (e.v == u && idxSet.contains(e.u)) nb = e.u;
                    if (nb >= 0 && !visited.contains(nb)) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
            if (visited.size() != idxSet.size()) {
                issues.add("WARN: Region#" + r.id + " has " +
                    (idxSet.size() - visited.size()) + " disconnected parcels");
            }
        }

        if (issues.isEmpty()) issues.add("ALL CHECKS PASSED (including connectivity)");
        return issues;
    }
}

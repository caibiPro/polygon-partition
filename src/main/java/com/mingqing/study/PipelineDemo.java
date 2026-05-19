package com.mingqing.study;

import java.util.*;

/**
 * 全流程演示：从原始图 → 最大生成树 → 递归切分 → 分区结果
 *
 * 数据流：
 *   buildGridEdges()        → List<Edge>（带权邻接图）
 *   Kruskal.maxSpanningTree → List<Edge>（树边，N-1条）
 *   toAdjList()             → List<List<Integer>>（树邻接表）
 *   partitionRecursively()  → List<List<Integer>>（每块节点集合）
 */
public class PipelineDemo {

    public static void main(String[] args) {
        int rows = 10000;
        int cols = 1;
        int n = rows * cols;           // 100 个节点
        int threshold = 500;           // 每块最多15个节点
        double keepRatio = 0.5;        // 随机保留70%的非骨架边
        long seed = 42L;

        // Step 1: 构造带权图
        List<Edge> edges = buildGridEdges(rows, cols, keepRatio, seed);
        System.out.println("=== Step 1: 构造图 ===");
        System.out.println("节点数: " + n);
        System.out.println("边数:   " + edges.size());
        printGrid(rows, cols, edges);

        // Step 2: 求最大生成树
        List<Edge> treeEdges = Kruskal.maxSpanningTree(n, edges);
        System.out.println("\n=== Step 2: 最大生成树 ===");
        System.out.println("树边数: " + treeEdges.size() + "（应为 " + (n - 1) + "）");
        printWeightStats("树边权重", treeEdges);
        printGrid(rows, cols, treeEdges);

        // Step 3: 树边 → 邻接表（你来实现）
        List<List<Integer>> tree = toAdjList(n, treeEdges);
        System.out.println("\n=== Step 3: 邻接表 ===");
        System.out.println("邻接表大小: " + tree.size());
        for (int i = 0; i < tree.size(); i++) {
            System.out.println("  节点" + i + " → " + tree.get(i));
        }


        // Step 4: 递归切分（你来实现）
        List<List<Integer>> partitions = new ArrayList<>();
        partitionRecursively(tree, allNodes(n), threshold, partitions);

        System.out.println("\n=== Step 4: 分区结果 ===");
        System.out.println("分区数: " + partitions.size());
        System.out.println("预期分区数: " + (int) Math.ceil((double) n / threshold));
        printPartitionStats(partitions, threshold);
    }

    /**
     *
     * 生成 rows×cols 网格图的带权边列表。
     *
     * 策略：
     *   a) 按列蛇形生成骨架（偶数列向下、奇数列向上），保证连通，权重设为1
     *   b) 收集所有非骨架的合法网格边（水平/垂直相邻），用 seed 随机打乱后保留 keepRatio 比例
     *   c) 非骨架边的权重 = (u + v + 1) % 50 + 1（制造差异化权重）
     *
     * 节点编号规则：node = row * cols + col
     *   水平边：(row, col) — (row, col+1)
     *   垂直边：(row, col) — (row+1, col)
     */
    static List<Edge> buildGridEdges(int rows, int cols, double keepRatio, long seed) {
        List<Edge> edges = new ArrayList<>();
        Set<Long> skeletonSet = new HashSet<>();

        // 按列蛇形生成骨架：偶数列向下，奇数列向上
        List<Integer> snakePath = new ArrayList<>();
        for (int col = 0; col < cols; col++) {
            if (col % 2 == 0) {
                for (int row = 0; row < rows; row++) snakePath.add(row * cols + col);
            } else {
                for (int row = rows - 1; row >= 0; row--) snakePath.add(row * cols + col);
            }
        }
        for (int i = 0; i < snakePath.size() - 1; i++) {
            int u = snakePath.get(i), v = snakePath.get(i + 1);
            edges.add(new Edge(u, v, 1));
            skeletonSet.add((long) Math.min(u, v) << 32 | Math.max(u, v));
        }

        List<Edge> candidates = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                // 水平边
                if (col + 1 < cols) {
                    int start = row * cols + col;
                    int end = start + 1;
                    if (skeletonSet.add((long) start << 32 | end)) {
                        candidates.add(new Edge(start, end, (start + end + 1) % 50 + 1));
                    }
                }

                // 竖直边
                if (row + 1 < rows) {
                    int start = row * cols + col;
                    int end = (row + 1) * cols + col;
                    if (skeletonSet.add((long) start << 32 | end)) {
                        candidates.add(new Edge(start, end, (start + end + 1) % 50 + 1));
                    }
                }
            }
        }

        Collections.shuffle(candidates, new Random(seed));
        int keep = (int) (candidates.size() * keepRatio);
        edges.addAll(candidates.subList(0, keep));

        return edges;
    }

    /**
     *
     * 把 Kruskal 返回的树边列表（List<Edge>）转换成无向邻接表（List<List<Integer>>）。
     *
     * 注意：树边是无向的，每条边 (u,v) 需要在 u 的邻居列表中加 v，也要在 v 的邻居列表中加 u。
     */
    static List<List<Integer>> toAdjList(int n, List<Edge> treeEdges) {
        return MiniDemo.toAdjList(n, treeEdges);
    }

    /**
     *
     * 递归地把 nodes 中的节点按树结构切分，直到每块大小 <= threshold。
     * 把每个最终块加入 result。
     *
     * 提示：
     *   - 如果 nodes.size() <= threshold，直接加入 result，返回
     *   - 否则，从 nodes 中提取诱导子树（见 inducedSubtree），调用 TreeCutResult.cutOnce() 切一刀
     *   - 对 cutResult.getPartA() 和 cutResult.getPartB() 分别递归
     *
     * 边界：如果 cutOnce 返回 null（无法切），直接把 nodes 加入 result
     */
    static void partitionRecursively(
            List<List<Integer>> fullTree,
            List<Integer> nodes,
            int threshold,
            List<List<Integer>> result) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("invalid threshold: " + threshold);
        }
        if (fullTree == null) {
            throw new IllegalArgumentException("invalid input fullTree");
        }
        if (nodes == null) {
            throw new IllegalArgumentException("invalid input nodes");
        }

        // 递归终止条件：当前这块已经够小了
        if (nodes.size() <= threshold) {
            result.add(nodes);
            return;
        }

        // 第一步：基于全局参照物和当前全局节点，提取出临时子树 (诱导子树)
        List<List<Integer>> inducedSubtree = inducedSubtree(fullTree, nodes);

        // 第二步：对提取出来的临时子树进行切分
        TreeCutResult treeCutResult = TreeCutResult.cutOnce(inducedSubtree);

        // 如果无法切分，直接把当前块加入结果
        if (treeCutResult == null) {
            result.add(nodes);
            return;
        }

        // 第三步：获取切分后的局部下标
        List<Integer> localPartA = treeCutResult.getPartA();
        List<Integer> localPartB = treeCutResult.getPartB();

        // 第四步：将局部下标翻译回全局下标
        List<Integer> globalPartA = localPartA.stream().map(nodes::get).toList();
        List<Integer> globalPartB = localPartB.stream().map(nodes::get).toList();

        // 第五步：带着全局参照物 fullTree 和新的全局下标，继续往下递归
        partitionRecursively(fullTree, globalPartA, threshold, result);
        partitionRecursively(fullTree, globalPartB, threshold, result);
    }

    /**
     * 从完整树中提取仅包含 nodes 中节点的诱导子树（邻接表）。
     * 已提供，不需要修改。
     *
     * 核心操作：对每个节点 u in nodes，只保留邻居中也在 nodes 里的边。
     */
    static List<List<Integer>> inducedSubtree(List<List<Integer>> fullTree, List<Integer> nodes) {
        Set<Integer> nodeSet = new HashSet<>(nodes);

        // 把全局节点ID重映射到局部[0, nodes.size())
        Map<Integer, Integer> globalToLocal = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            globalToLocal.put(nodes.get(i), i);
        }

        List<List<Integer>> sub = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            sub.add(new ArrayList<>());
        }

        for (int i = 0; i < nodes.size(); i++) {
            int globalU = nodes.get(i);
            for (int globalV : fullTree.get(globalU)) {
                if (nodeSet.contains(globalV)) {
                    sub.get(i).add(globalToLocal.get(globalV));
                }
            }
        }

        return sub;
    }

    // ─── 可视化方法（已提供）──────────────────────────────────

    static void printGrid(int rows, int cols, List<Edge> edges) {
        Map<Long, Integer> edgeMap = new HashMap<>();
        int maxWeight = 0;
        for (Edge edge : edges) {
            int a = Math.min(edge.u(), edge.v());
            int b = Math.max(edge.u(), edge.v());
            int w = edge.weight();
            edgeMap.put((long) a << 32 | b, w);
            maxWeight = Math.max(maxWeight, w);
        }

        int maxNode = rows * cols - 1;
        int L = Math.max(2, Math.max(String.valueOf(maxNode).length(), String.valueOf(maxWeight).length()));

        String nodeFmt = "[%0" + L + "d]";
        String hEdgeFmt = " ──(%" + L + "d)── ";
        String hEmpty = String.format("%" + (L + 8) + "s", "");

        String vPipe = " │" + String.format("%" + L + "s", "");
        String vWeightFmt = "(%" + L + "d)";
        String vEmpty = String.format("%" + (L + 2) + "s", "");

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            // 1. 打印节点层与水平边
            for (int c = 0; c < cols; c++) {
                int node = r * cols + c;
                sb.append(String.format(nodeFmt, node));
                if (c < cols - 1) {
                    long key = (long) node << 32 | (node + 1);
                    sb.append(edgeMap.containsKey(key) ? String.format(hEdgeFmt, edgeMap.get(key)) : hEmpty);
                }
            }
            sb.append("\n");

            // 2. 打印垂直连接层
            if (r < rows - 1) {
                // 【重构核心】：提前构建纯垂直线段（无权重）
                StringBuilder vLineBuilder = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    int node = r * cols + c;
                    long key = (long) node << 32 | (node + cols);
                    vLineBuilder.append(edgeMap.containsKey(key) ? vPipe : vEmpty);
                    if (c < cols - 1) vLineBuilder.append(hEmpty);
                }
                String vLineStr = vLineBuilder.toString();

                // 上半截垂直线
                sb.append(vLineStr).append("\n");

                // 中间垂直边权重
                for (int c = 0; c < cols; c++) {
                    int node = r * cols + c;
                    long key = (long) node << 32 | (node + cols);
                    sb.append(edgeMap.containsKey(key) ? String.format(vWeightFmt, edgeMap.get(key)) : vEmpty);
                    if (c < cols - 1) sb.append(hEmpty);
                }
                sb.append("\n");

                // 下半截垂直线（直接复用之前构建的字符串）
                sb.append(vLineStr).append("\n");
            }
        }
        System.out.println(sb);
    }

    // ─── 辅助方法（已提供）─────────────────────────────────

    private static List<Integer> allNodes(int n) {
        List<Integer> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) nodes.add(i);
        return nodes;
    }

    private static void printWeightStats(String label, List<Edge> edges) {
        // Edge 没有暴露 weight，用 compareTo 的相对顺序做极值展示
        // 若你给 Edge 加了 getWeight()，可以在这里打印 min/max/avg
        System.out.println(label + ": 共 " + edges.size() + " 条");
    }

    private static void printPartitionStats(List<List<Integer>> partitions, int threshold) {
        int min = Integer.MAX_VALUE, max = 0, total = 0;
        int violations = 0;
        for (List<Integer> p : partitions) {
            int sz = p.size();
            min = Math.min(min, sz);
            max = Math.max(max, sz);
            total += sz;
            if (sz > threshold) violations++;
        }
        System.out.printf("分区大小: min=%d, max=%d, avg=%.1f%n", min, max, (double) total / partitions.size());
        System.out.println("超阈值分区数: " + violations + "（应为0）");
        System.out.println("节点总数: " + total + "（应为100）");

        // 打印每个分区的大小分布
        System.out.print("各分区大小: ");
        partitions.stream()
                .map(List::size)
                .sorted(Comparator.reverseOrder())
                .forEach(s -> System.out.print(s + " "));
        System.out.println();
    }
}

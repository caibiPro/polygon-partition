package com.mingqing.study;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极简全流程演示：6个节点，数据全部硬编码，跑通后能看到每一步的中间结果。
 *
 * 图结构：
 *
 *   0 —5— 1 —3— 2
 *   |           |
 *   4     7     8
 *   |           |
 *   3 —6———————4
 *         |
 *         4 ——— (1-4边，权重=7)
 *
 * 实际边列表（见下方 edges）：
 *   0-1 权重5, 1-2 权重3, 2-4 权重8,
 *   3-4 权重6, 0-3 权重4, 1-4 权重7
 */
public class MiniDemo {

    public static void main(String[] args) {

        // ── Step 1: 定义原始图 ──────────────────────────────────────
        int n = 5;
        List<Edge> edges = Arrays.asList(
                new Edge(0, 1, 5),
                new Edge(1, 2, 3),
                new Edge(2, 4, 8),
                new Edge(3, 4, 6),
                new Edge(0, 3, 4),
                new Edge(1, 4, 7)
        );

        System.out.println("=== Step 1: 原始图 ===");
        System.out.println("节点数: " + n);
        System.out.println("边数:   " + edges.size());
        for (Edge e : edges) {
            System.out.println("  " + e.u() + " -- " + e.v() + "  权重=" + e.weight());
        }

        // ── Step 2: 求最大生成树 ─────────────────────────────────────
        List<Edge> treeEdges = Kruskal.maxSpanningTree(n, edges);

        System.out.println("\n=== Step 2: 最大生成树（Kruskal）===");
        System.out.println("树边数: " + treeEdges.size() + "（应为 " + (n - 1) + "）");
        for (Edge e : treeEdges) {
            System.out.println("  " + e.u() + " -- " + e.v() + "  权重=" + e.weight());
        }
        System.out.println("被丢弃的边数: " + (edges.size() - treeEdges.size()));

        // ── Step 3: 树边列表 → 邻接表 ────────────────────────────────
        List<List<Integer>> tree = toAdjList(n, treeEdges);

        System.out.println("\n=== Step 3: 树的邻接表 ===");
        for (int i = 0; i < tree.size(); i++) {
            System.out.println("  节点" + i + " 的邻居: " + tree.get(i));
        }

        // ── Step 4: 计算子树大小 ──────────────────────────────────────
        int[] subtreeSize = new int[n];
        int[] parentArray = new int[n];
        Arrays.fill(parentArray, -1);
        TreePartition.dfsSubtreeSize(0, tree, subtreeSize, parentArray);

        System.out.println("\n=== Step 4: 子树大小（根=节点0）===");
        for (int i = 0; i < n; i++) {
            System.out.println("  节点" + i
                    + "  subtreeSize=" + subtreeSize[i]
                    + "  parent=" + parentArray[i]);
        }

        // ── Step 5: 找最佳切边 ────────────────────────────────────────
        CutEdge best = CutEdge.findBestBalancedCut(parentArray, subtreeSize);

        System.out.println("\n=== Step 5: 最佳切边 ===");
        if (best == null) {
            System.out.println("  无法切分");
        } else {
            System.out.println("  切断边: " + best.getParent() + " -- " + best.getChild());
            System.out.println("  左侧节点数: " + best.getLeftSize());
            System.out.println("  右侧节点数: " + best.getRightSize());
            System.out.println("  不均衡度:   " + best.getImbalance());
        }

        // ── Step 6: 收集切后两侧节点 ──────────────────────────────────
        System.out.println("\n=== Step 6: 切后两侧节点 ===");
        List<Integer> partA = CutEdge.collectSubtreeAfterCut(best.getChild(), best.getParent(), tree);
        List<Integer> partB = CutEdge.collectSubtreeAfterCut(best.getParent(), best.getChild(), tree);
        System.out.println("  部分A（子树侧）: " + partA);
        System.out.println("  部分B（另一侧）: " + partB);
        System.out.println("  A+B总节点数: " + (partA.size() + partB.size()) + "（应为" + n + "）");
    }

    /**
     *
     * 输入: n=5，treeEdges = [(2,4), (1,4), (3,4), (0,1)]（举例）
     * 输出: List<List<Integer>>，大小为n，
     *       其中 result.get(u) 包含 u 的所有树邻居
     *
     * 提示: 每条边 (u,v) 是无向的——u的邻居要加v，v的邻居也要加u。
     */
    static List<List<Integer>> toAdjList(int n, List<Edge> treeEdges) {
        if (treeEdges == null) {
            throw new IllegalArgumentException("treeEdges is null");
        }
        if (treeEdges.size() != n - 1) {
            throw new IllegalArgumentException("not valid max spanning tree");
        }

        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(new ArrayList<>());
        }

        for (Edge e : treeEdges) {
            int u = e.u();
            int v = e.v();
            if (u >= n || v >= n) {
                throw new IllegalArgumentException("invalid edge");
            }

            result.get(u).add(v);
            result.get(v).add(u);
        }

        return result;
    }
}

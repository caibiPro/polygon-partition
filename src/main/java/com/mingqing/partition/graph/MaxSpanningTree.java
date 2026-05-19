package com.mingqing.partition.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MaxSpanningTree {

    private MaxSpanningTree() {
    }

    /**
     * 对给定的带权无向图计算最大生成树，返回 N-1 条边。
     *
     * @param n     节点数量（图斑数）
     * @param edges 所有候选边（由 AdjacencyGraphBuilder.build() 生成）
     * @return 最大生成树的边列表，共 n-1 条
     * @throws IllegalArgumentException 如果 n < 1 或 edges 为 null
     * @throws IllegalStateException    如果图不连通，无法构成生成树
     *
     */
    public static List<WeightedEdge> compute(int n, List<WeightedEdge> edges) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        if (n == 1) {
            return new ArrayList<>();
        }
        if (edges == null) {
            throw new IllegalArgumentException("edges must not be null");
        }
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("edges must not be empty when n > 1");
        }

        List<WeightedEdge> sorted = edges.stream()
                .sorted((a, b) -> Double.compare(b.weight(), a.weight())).toList();

        UnionFind uf = new UnionFind(n);
        List<WeightedEdge> result = new ArrayList<>();

        for (WeightedEdge edge : sorted) {
            int u = edge.u();
            int v = edge.v();

            if (!uf.connected(u, v)) {
                uf.union(u, v);
                result.add(edge);

                if (result.size() == n - 1) {
                    break;
                }
            }
        }

        if (result.size() != n - 1) {
            throw new IllegalStateException("graph is disconnected");
        }

        return result;
    }
}

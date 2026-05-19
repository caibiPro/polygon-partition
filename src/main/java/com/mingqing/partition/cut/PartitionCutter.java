package com.mingqing.partition.cut;

import com.mingqing.partition.graph.UnionFind;
import com.mingqing.partition.graph.WeightedEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PartitionCutter {

    private PartitionCutter() {
    }

    /**
     * 在最大生成树上切掉权重最小的 k-1 条边，返回 k 个连通分组。
     * 每个分组是节点编号（图斑下标）的列表。
     *
     * @param n       节点总数
     * @param mstEdges 最大生成树的边（共 n-1 条，由 MaxSpanningTree.compute 生成）
     * @param k       目标分组数
     * @return k 个分组，每个分组包含属于该组的节点编号
     */
    public static List<List<Integer>> cut(int n, List<WeightedEdge> mstEdges, int k) {
        if (n < 1) throw new IllegalArgumentException("n must be >= 1");
        if (mstEdges == null) throw new IllegalArgumentException("mstEdges cannot be null");
        if (k < 1 || k > n) throw new IllegalArgumentException("k must be between 1 and n");

        // 按权重降序排列，保留前 (n-k) 条最强的边，切掉最弱的 (k-1) 条
        List<WeightedEdge> kept = mstEdges.stream()
                .sorted(Comparator.comparingDouble(WeightedEdge::weight).reversed())
                .limit(n - k)
                .toList();

        // 用保留的边重建 UnionFind，剩下的连通块就是分组
        UnionFind uf = new UnionFind(n);
        for (WeightedEdge edge : kept) {
            uf.union(edge.u(), edge.v());
        }

        Map<Integer, List<Integer>> groupMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = uf.find(i);
            groupMap.computeIfAbsent(root, key -> new ArrayList<>()).add(i);
        }
        return groupMap.values().stream().toList();
    }
}

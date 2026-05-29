package com.mingqing.partition.cut.algorithm;

import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.graph.UnionFind;
import com.mingqing.partition.graph.WeightedEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局最弱边切分算法 (Global Weakest Edge Algorithm)。
 * 特征：非递归，基于权重排序和并查集，由目标组数 k 驱动。
 */
public class GlobalWeakestEdgeAlgorithm implements TreePartitionAlgorithm {

    @Override
    public List<List<Integer>> partition(
            int n,
            List<WeightedEdge> mstEdges,
            PartitionContext context) {

        int k = context.targetGroupCount();

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
        return new ArrayList<>(groupMap.values());
    }
}

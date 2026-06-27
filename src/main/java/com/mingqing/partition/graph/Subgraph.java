package com.mingqing.partition.graph;

import java.util.List;

/**
 * 导出子图 + 稠密重编号工具（graph 层）。
 * <p>
 * 给定父图的一个节点子集，抽取只含子集内部边的子图，并把节点重新编号成
 * 紧凑的 0..k-1 局部 ID——这是 {@link MaxSpanningTree} / {@link UnionFind}
 * 等要求稠密编号的算法的通用前置步骤。
 * <p>
 * 约定：{@code nodes} 列表本身就是 local→global 的查找表
 * （下标 = local ID，值 = global ID）；其逆向 global→local 在内部构建。
 */
public final class Subgraph {

    private Subgraph() {
    }

    /**
     * 从父图加权边集中导出 {@code nodes} 子集的局部加权子图。
     * <p>
     * 只保留两端都落在 {@code nodes} 内的边，并把端点从 global ID 重映射为
     * local ID（0..nodes.size()-1），权重原样保留。
     *
     * @param nodes 子集的 global ID 列表（同时充当 local→global 查找表）
     * @param edges 父图的全部加权边（端点用 global ID）
     * @return 局部编号的加权边表，可直接喂给 {@link MaxSpanningTree#compute}
     */
    public static List<WeightedEdge> induce(List<Integer> nodes, List<WeightedEdge> edges) {
        // TODO(human)
        return null;
    }
}

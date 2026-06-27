package com.mingqing.partition.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出子图 + 稠密重编号工具（graph 层）。
 * <p>
 * 给定父图的一个节点子集，抽取只含子集内部边的子图，并把节点重新编号成
 * 紧凑的 0..k-1 局部 ID——这是 {@link MaxSpanningTree} / 树切分等要求稠密
 * 编号的算法的通用前置步骤。
 * <p>
 * 提供两种表示的导出：{@link #induce} 处理加权边表（喂 MST），
 * {@link #induceAdjacency} 处理无权邻接表（喂树 DFS）。二者共享同一个
 * global→local 索引构建原子 {@link #indexMap}。
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
        Map<Integer, Integer> toLocal = indexMap(nodes);

        List<WeightedEdge> localEdges = new ArrayList<>();
        for (WeightedEdge e : edges) {
            Integer localU = toLocal.get(e.u());
            Integer localV = toLocal.get(e.v());
            // get()==null 即"端点不在子集内"，一次查询同时完成判存在 + 取 local ID
            if (localU != null && localV != null) {
                localEdges.add(new WeightedEdge(localU, localV, e.weight()));
            }
        }
        return localEdges;
    }

    /**
     * 从父图邻接表中导出 {@code nodes} 子集的局部无权邻接表。
     * <p>
     * 只保留两端都落在 {@code nodes} 内的邻接关系，并重映射为 local ID。
     * 适用于已建好邻接表、递归各层反复导出子图的场景（如树切分）。
     *
     * @param nodes     子集的 parent ID 列表（同时充当 local→parent 查找表）
     * @param adjacency 父图邻接表（下标用 parent ID）
     * @return 局部编号的邻接表
     */
    public static List<List<Integer>> induceAdjacency(List<Integer> nodes, List<List<Integer>> adjacency) {
        int localN = nodes.size();
        Map<Integer, Integer> toLocal = indexMap(nodes);

        List<List<Integer>> localAdj = new ArrayList<>(localN);
        for (int i = 0; i < localN; i++) {
            localAdj.add(new ArrayList<>());
        }

        for (int localU = 0; localU < localN; localU++) {
            int parentU = nodes.get(localU);
            for (int parentV : adjacency.get(parentU)) {
                Integer localV = toLocal.get(parentV);
                if (localV != null) {
                    localAdj.get(localU).add(localV);
                }
            }
        }
        return localAdj;
    }

    /**
     * 共享原子：构建 global/parent → local 的逆向索引。
     * 只用于 {@code get(key)} 查询、从不遍历，故用 {@link HashMap}。
     */
    private static Map<Integer, Integer> indexMap(List<Integer> nodes) {
        Map<Integer, Integer> map = new HashMap<>((int) (nodes.size() / 0.75f) + 1);
        for (int local = 0; local < nodes.size(); local++) {
            map.put(nodes.get(local), local);
        }
        return map;
    }
}

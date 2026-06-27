package com.mingqing.partition.partitioner;

import com.mingqing.partition.cut.algorithm.TreePartitionAlgorithm;
import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.domain.Plot;
import com.mingqing.partition.graph.AdjacencyGraphBuilder;
import com.mingqing.partition.graph.MaxSpanningTree;
import com.mingqing.partition.graph.UnionFind;
import com.mingqing.partition.graph.WeightedEdge;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 村级任务包划分器（集成层）。
 * <p>
 * 输入一个村的全部地块，输出若干"任务包"。约束：
 * 1. 每包地块数 ≤ maxGroupSize（硬上限）
 * 2. 每包内地块尽量空间聚合（软约束）
 * 3. 各包大小尽量均衡
 * <p>
 * 不变量：每个 Plot 出现且仅出现一次（集合划分）。
 */
public class VillagePartitioner {

    private final TreePartitionAlgorithm algorithm;
    private final int maxGroupSize;

    public VillagePartitioner(TreePartitionAlgorithm algorithm, int maxGroupSize) {
        if (maxGroupSize <= 0) throw new IllegalArgumentException("maxGroupSize must be positive");
        this.algorithm = algorithm;
        this.maxGroupSize = maxGroupSize;
    }

    public List<List<Plot>> partition(List<Plot> plots) {
        if (plots == null) throw new IllegalArgumentException("plots cannot be null");
        if (plots.isEmpty()) return List.of();

        List<Geometry> geometries = plots.stream().map(Plot::geometry).toList();
        List<WeightedEdge> globalEdges = AdjacencyGraphBuilder.build(geometries);

        // 全局下标视角：用 UnionFind 找连通分量
        Map<Integer, List<Integer>> components = findComponents(plots.size(), globalEdges);

        // 每个分量切成若干"原始组"（仍是全局下标）
        List<List<Integer>> rawGroups = new ArrayList<>();
        for (List<Integer> component : components.values()) {
            if (component.size() <= maxGroupSize) {
                rawGroups.add(component);
            } else {
                rawGroups.addAll(partitionComponent(component, globalEdges));
            }
        }

        // 小组贪心合并至接近 maxGroupSize
        List<List<Integer>> mergedGroups = mergeSmallGroups(rawGroups);

        // 全局下标 → Plot
        return mergedGroups.stream()
                .map(group -> group.stream().map(plots::get).toList())
                .toList();
    }

    /**
     * 用 UnionFind 把全村地块按邻接关系聚成连通分量。
     *
     * @return root 全局下标 → 该分量包含的全局下标列表
     */
    private Map<Integer, List<Integer>> findComponents(int n, List<WeightedEdge> globalEdges) {
        UnionFind uf = new UnionFind(n);
        for (WeightedEdge e : globalEdges) {
            uf.union(e.u(), e.v());
        }
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(i);
        }
        return components;
    }

    /**
     * 对一个超过 maxGroupSize 的连通分量执行树切分。
     * <p>
     * 坐标系切换：MST / RecursivePeeling 要求节点是稠密局部下标 0..n-1，
     * 因此这里把分量的全局下标重映射成局部下标，切完再映射回去。
     *
     * @param globalNodes 分量内的全局下标集合
     * @param globalEdges 全村的全局边集
     * @return 若干组，每组是全局下标列表
     */
    private List<List<Integer>> partitionComponent(List<Integer> globalNodes, List<WeightedEdge> globalEdges) {
        int localN = globalNodes.size();

        Map<Integer, Integer> globalToLocal = new LinkedHashMap<>((int) (localN / 0.75f) + 1);
        for (int local = 0; local < localN; local++) {
            globalToLocal.put(globalNodes.get(local), local);
        }

        List<WeightedEdge> localEdges = new ArrayList<>();
        for (WeightedEdge e : globalEdges) {
            Integer lu = globalToLocal.get(e.u());
            Integer lv = globalToLocal.get(e.v());
            if (lu != null && lv != null) {
                localEdges.add(new WeightedEdge(lu, lv, e.weight()));
            }
        }

        List<WeightedEdge> mst = MaxSpanningTree.compute(localN, localEdges);
        List<List<Integer>> localGroups =
                algorithm.partition(localN, mst, new PartitionContext(maxGroupSize, 0));

        // local → global
        List<List<Integer>> result = new ArrayList<>(localGroups.size());
        for (List<Integer> localGroup : localGroups) {
            List<Integer> globalGroup = new ArrayList<>(localGroup.size());
            for (int local : localGroup) {
                globalGroup.add(globalNodes.get(local));
            }
            result.add(globalGroup);
        }
        return result;
    }

    /**
     * 把过小的组贪心合并到接近 maxGroupSize 的任务包。
     * <p>
     * 输入是切分后的所有原始组（全局下标），输出是合并后的组。
     * 必须保持集合划分不变量：每个全局下标出现且仅出现一次。
     */
    private List<List<Integer>> mergeSmallGroups(List<List<Integer>> groups) {
        // TODO(human)
        return null;
    }
}

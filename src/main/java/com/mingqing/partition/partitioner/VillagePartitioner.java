package com.mingqing.partition.partitioner;

import com.mingqing.partition.cut.algorithm.TreePartitionAlgorithm;
import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.domain.Plot;
import com.mingqing.partition.graph.AdjacencyGraphBuilder;
import com.mingqing.partition.graph.MaxSpanningTree;
import com.mingqing.partition.graph.Subgraph;
import com.mingqing.partition.graph.UnionFind;
import com.mingqing.partition.graph.WeightedEdge;
import com.mingqing.partition.merge.Cluster;
import com.mingqing.partition.merge.SpatialMerger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

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
    private final SpatialMerger merger;
    private final int maxGroupSize;

    public VillagePartitioner(TreePartitionAlgorithm algorithm, SpatialMerger merger, int maxGroupSize) {
        if (maxGroupSize <= 0) throw new IllegalArgumentException("maxGroupSize must be positive");
        this.algorithm = algorithm;
        this.merger = merger;
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

        // 按地理邻近度合并小组至接近 maxGroupSize
        List<List<Integer>> mergedGroups = mergeSmallGroups(rawGroups, geometries);

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

        // 阶段①：导出该分量的局部加权子图（global→local 重编号）
        List<WeightedEdge> localEdges = Subgraph.induce(globalNodes, globalEdges);

        // 阶段②③：最大生成树 + 递归切分
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
     * 按地理邻近度把过小的组合并成接近 maxGroupSize 的任务包。
     * <p>
     * 关键：跨连通分量的组之间不共享边界，所以"聚合"的可操作含义是
     * 「地理邻近」——把质心彼此靠近的小簇拼到同一个包，让外业人员
     * 领到的包集中在一片区域，而不是散落全村。
     * <p>
     * 必须保持集合划分不变量：每个全局下标出现且仅出现一次；
     * 且合并后每个包仍 ≤ maxGroupSize。
     *
     * @param groups     切分后的原始组（全局下标）
     * @param geometries 全村几何（下标与 plots 对齐），用于计算质心距离
     */
    private List<List<Integer>> mergeSmallGroups(List<List<Integer>> groups, List<Geometry> geometries) {
        // 把每个组转成带代表点的 Cluster（这里是 GIS → 纯数值的边界）
        List<Cluster> clusters = new ArrayList<>(groups.size());
        for (List<Integer> group : groups) {
            clusters.add(toCluster(group, geometries));
        }
        // 具体用哪种空间装箱策略由注入的 merger 决定（Morton / 单轴 / …）
        return merger.merge(clusters, maxGroupSize);
    }

    /**
     * 把一组地块打包成 {@link Cluster}：代表点取各成员质心坐标的算术平均
     * （足够近似邻近度，无需昂贵的几何 union）。
     */
    private static Cluster toCluster(List<Integer> group, List<Geometry> geometries) {
        double sumX = 0, sumY = 0;
        for (int idx : group) {
            Point c = geometries.get(idx).getCentroid();
            sumX += c.getX();
            sumY += c.getY();
        }
        return new Cluster(group, sumX / group.size(), sumY / group.size());
    }
}

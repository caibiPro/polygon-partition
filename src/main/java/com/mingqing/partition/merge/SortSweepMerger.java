package com.mingqing.partition.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 最简单的合并器：单轴排序 + 顺序装箱（baseline）。
 * <p>
 * 思路：把所有簇按 X 坐标排序（X 相同再按 Y），然后沿这个一维顺序
 * 顺序装箱——累加到当前包，若再加一个簇会超 capacity 就封箱、另起一包。
 * <p>
 * 优：极简、易懂、确定性、O(n log n)。
 * 劣：只在一个轴上聚合，垂直方向不管——包会变成纵贯全村的"长条"。
 * 适合作为对照基线，用来理解「排序顺序如何决定包的形状」，再对比 Morton。
 */
public class SortSweepMerger implements SpatialMerger {

    @Override
    public List<List<Integer>> merge(List<Cluster> clusters, int capacity) {
        List<Cluster> sortedClusters = clusters.stream()
                .sorted(Comparator.comparingDouble(Cluster::x).thenComparing(Cluster::y))
                .toList();

        List<List<Integer>> result = new ArrayList<>();
        List<Integer> current = new ArrayList<>();

        for (Cluster cluster : sortedClusters) {
            if (cluster.size() > capacity) {
                throw new IllegalArgumentException("size of cluster to merge is greater than capacity.");
            }
            if (current.size() + cluster.size() > capacity) {
                result.add(List.copyOf(current));
                current.clear();
            }

            current.addAll(cluster.members());
        }

        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }

        return result;
    }
}

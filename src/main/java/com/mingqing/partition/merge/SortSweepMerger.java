package com.mingqing.partition.merge;

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
        // TODO(human):
        //  1. 复制 clusters 并按 x() 升序排序（x 相等时按 y() 升序）。
        //     提示：clusters.stream().sorted(Comparator.comparingDouble(...).thenComparing...).toList()
        //  2. 沿排序顺序顺序装箱：
        //       - 维护 current（当前包的成员）。
        //       - 对每个簇 c：若 current 非空 且 current.size()+c.size() > capacity，
        //         先把 current 收进结果、再开一个新 current。
        //       - 把 c.members() 加进 current。
        //  3. 循环结束后，别忘了把最后一个非空 current 收进结果。
        //  不变量：每个成员出现且仅出现一次；每个包 ≤ capacity。
        return null;
    }
}

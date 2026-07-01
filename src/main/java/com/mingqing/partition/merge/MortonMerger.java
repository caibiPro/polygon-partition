package com.mingqing.partition.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Morton（Z-order）空间填充曲线的合并器。
 * <p>
 * 排序用 {@link MortonOrder}（紧凑度），装箱用 balanced-target（均衡）：
 * 沿一维顺序、达到摊平目标 target 即封箱。
 * <p>
 * 优：局部性好、<b>无距离阈值/无网格尺寸等参数</b>、O(n log n)、可扩展。
 * 劣：装箱不跨顺序搬运，接近上限的大簇会孤立邻近小簇 → 尾部欠装包
 *（若要消化孤儿见 {@link LocalBestFitMortonMerger}）。
 */
public class MortonMerger implements SpatialMerger {

    @Override
    public List<List<Integer>> merge(List<Cluster> clusters, int capacity) {
        if (clusters.isEmpty()) return List.of();
        // 契约（见 SpatialMerger）：单簇本应已 ≤ capacity；超了即上游 bug，fail-fast
        for (Cluster c : clusters) {
            if (c.size() > capacity) {
                throw new IllegalArgumentException(
                        "cluster size " + c.size() + " exceeds capacity " + capacity);
            }
        }
        List<Cluster> ordered = MortonOrder.sort(clusters);
        int target = balancedTarget(clusters, capacity);
        return balancedPack(ordered, capacity, target);
    }

    /**
     * 均衡目标包大小：先按硬上限定出最少包数 k=⌈total/capacity⌉，
     * 再把总量摊平到 k 个包，target=⌈total/k⌉（恒 ≤ capacity）。
     * 让各包奔着 target 而非顶满 capacity，slack 被均匀分摊而非堆在尾部。
     */
    private static int balancedTarget(List<Cluster> clusters, int capacity) {
        int total = clusters.stream().mapToInt(Cluster::size).sum();
        if (total == 0) return capacity;
        int k = (int) Math.ceil((double) total / capacity);
        return (int) Math.ceil((double) total / k);
    }

    /**
     * 沿已排序顺序的均衡装箱：达到软目标 target 即封箱，但绝不破硬上限 capacity。
     * 排序顺序不变 → 紧凑度不受影响，只把封箱点提前并摊匀。
     * 前提：单个簇本身已 ≤ capacity（由切分阶段保证）。
     * <p>
     * 注：真实四川数据上聚合 std 无明显改善（大连通分量当"路障"主导不均衡），
     * 但在簇尺寸更小/更均匀时生效，且避免尾部出现过小包。
     */
    private static List<List<Integer>> balancedPack(List<Cluster> ordered, int capacity, int target) {
        List<List<Integer>> bins = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (Cluster c : ordered) {
            boolean overCapacity = current.size() + c.size() > capacity; // 硬上限：绝不可破
            boolean reachedTarget = current.size() >= target;            // 软目标：够了就封
            if (!current.isEmpty() && (overCapacity || reachedTarget)) {
                bins.add(current);
                current = new ArrayList<>();
            }
            current.addAll(c.members());
        }
        if (!current.isEmpty()) bins.add(current);
        return bins;
    }
}

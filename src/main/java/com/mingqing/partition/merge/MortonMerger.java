package com.mingqing.partition.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 Morton（Z-order）空间填充曲线的合并器。
 * <p>
 * 思路：把每个簇的二维坐标量化后位交错成一个一维 Morton 码，按码排序，
 * 使「二维上邻近」转成「一维上相邻」；随后沿一维顺序贪心装箱。
 * <p>
 * 优：局部性好、<b>无距离阈值/无网格尺寸等参数</b>、O(n log n)、可扩展，
 * 是 GIS / 空间数据库分片的通用做法。
 * 劣：概念较重（位交错）；Morton 在 2×2 块切换处有对角跳变（Hilbert 更优
 * 但实现更复杂）；当前装箱为「贪心填满」，最后一包可能偏小。
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
        List<Cluster> ordered = sortByLocality(clusters);
        return sequentialPack(ordered, capacity);
    }

    /**
     * 沿已排序顺序贪心装箱：累加直到再加一个簇会超 capacity 就封箱另起。
     * 前提：单个簇本身已 ≤ capacity（由切分阶段保证）。
     */
    private static List<List<Integer>> sequentialPack(List<Cluster> ordered, int capacity) {
        List<List<Integer>> bins = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (Cluster c : ordered) {
            if (!current.isEmpty() && current.size() + c.size() > capacity) {
                bins.add(current);
                current = new ArrayList<>();
            }
            current.addAll(c.members());
        }
        if (!current.isEmpty()) bins.add(current);
        return bins;
    }

    /** 按 Morton 码排序，把二维邻近转成一维相邻。 */
    private static List<Cluster> sortByLocality(List<Cluster> clusters) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Cluster c : clusters) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
        }
        double spanX = maxX - minX, spanY = maxY - minY;
        double fMinX = minX, fMinY = minY;

        List<Cluster> sorted = new ArrayList<>(clusters);
        sorted.sort(Comparator.comparingLong(c -> {
            int qx = quantize(c.x(), fMinX, spanX);
            int qy = quantize(c.y(), fMinY, spanY);
            return morton(qx, qy);
        }));
        return sorted;
    }

    /** 把坐标归一化量化到 16-bit 整数网格 [0, 65535]。 */
    private static int quantize(double value, double min, double span) {
        if (span <= 0) return 0;
        return (int) ((value - min) / span * 0xFFFF);
    }

    /** 交错 x、y 的低 16 位生成 32-bit Morton 码。包级可见以便探索测试直接调用。 */
    static long morton(int x, int y) {
        return spreadBits(x) | (spreadBits(y) << 1);
    }

    /** 把 16-bit 值的每一位散开到偶数位（位间插 0）。包级可见以便探索测试直接调用。 */
    static long spreadBits(int v) {
        long x = v & 0xFFFFL;
        x = (x | (x << 8)) & 0x00FF00FFL;
        x = (x | (x << 4)) & 0x0F0F0F0FL;
        x = (x | (x << 2)) & 0x33333333L;
        x = (x | (x << 1)) & 0x55555555L;
        return x;
    }
}

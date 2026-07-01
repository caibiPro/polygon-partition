package com.mingqing.partition.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Morton（Z-order）空间填充曲线排序工具：把二维邻近转成一维相邻。
 * <p>
 * 这是「排序 ⊥ 装箱」中的排序一侧——决定包的<b>紧凑度</b>。多个 merger
 * （MortonMerger / LocalBestFitMortonMerger）共用同一套排序，各自只在
 * 装箱策略上不同。
 */
public final class MortonOrder {

    private MortonOrder() {
    }

    /** 按 Morton 码排序，使二维上邻近的簇在一维序列里也相邻。 */
    public static List<Cluster> sort(List<Cluster> clusters) {
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

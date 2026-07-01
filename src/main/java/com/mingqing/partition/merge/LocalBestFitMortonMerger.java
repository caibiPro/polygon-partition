package com.mingqing.partition.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * Morton 排序 + 局部最佳装填（windowed local best-fit）合并器。
 * <p>
 * 与 {@link MortonMerger} 共用 {@link MortonOrder} 排序（紧凑度相同来源），
 * 区别在装箱：每个包在封箱前，向前看 {@code window} 个位置，把能塞进剩余
 * 空位的小簇<b>就近捞进来填缝</b>。这样接近上限的大簇不再孤立邻近小簇——
 * 孤儿被大包的 slack 吸收，尾部欠装包大幅减少。
 * <p>
 * {@code window} 是「均衡 ⊥ 紧凑」的旋钮：
 * <ul>
 *   <li>越小 → 越接近严格顺序（最紧凑，但孤儿多）；</li>
 *   <li>越大 → 填料范围越广（越均衡，但跨 Morton 顺序越远 → 紧凑度下降）。</li>
 * </ul>
 * 取一个适中的 window，只从地理上很近的范围抓填料，是「紧凑优先」下的折中点。
 */
public class LocalBestFitMortonMerger implements SpatialMerger {

    private final int window;

    /**
     * @param window 每个包封箱前向前看的位置数（≥1）。需 ≥ 装满一个包所需的
     *               典型簇数才能有效填缝（如 capacity/簇均值）。
     */
    public LocalBestFitMortonMerger(int window) {
        if (window < 1) throw new IllegalArgumentException("window must be >= 1");
        this.window = window;
    }

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
        return localBestFitPack(MortonOrder.sort(clusters), capacity);
    }

    /**
     * 局部最佳装填：以每个未用簇为包起点，向前看 window 个位置，
     * 把能塞进剩余空位的簇就近收入，填满即止。
     */
    private List<List<Integer>> localBestFitPack(List<Cluster> ordered, int capacity) {
        int n = ordered.size();
        boolean[] used = new boolean[n];
        List<List<Integer>> bins = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (used[i]) continue;

            List<Integer> bin = new ArrayList<>(ordered.get(i).members());
            int size = ordered.get(i).size();
            used[i] = true;

            int limit = Math.min(n, i + 1 + window);
            for (int j = i + 1; j < limit && size < capacity; j++) {
                if (used[j]) continue;
                int cs = ordered.get(j).size();
                if (size + cs <= capacity) {       // 能塞进剩余空位 → 就近捞进来填缝
                    bin.addAll(ordered.get(j).members());
                    size += cs;
                    used[j] = true;
                }
            }
            bins.add(bin);
        }
        return bins;
    }
}

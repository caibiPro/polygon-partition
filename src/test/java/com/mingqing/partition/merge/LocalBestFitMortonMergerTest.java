package com.mingqing.partition.merge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalBestFitMortonMergerTest {

    private final SpatialMerger merger = new LocalBestFitMortonMerger(8);

    /**
     * 孤儿吸收：序列(大小) [480] [2] [499]，capacity=500。
     * 严格顺序会把中间的 2 困成孤儿小包；局部 best-fit 应让 480 的包
     * 越过它向前看，把 2 捞进 slack → 不再出现 size-2 的孤儿包。
     * 沿 x 轴排开保证 Morton 顺序 = id 顺序。
     */
    @Test
    void absorbsOrphanIntoLargeBinSlack() {
        List<Cluster> clusters = List.of(
                new Cluster(members(0, 480), 0, 0),   // 480 个成员
                new Cluster(List.of(480), 1, 0),      // 单个小簇（id=480）
                new Cluster(members(481, 499), 2, 0)  // 499 个成员
        );

        List<List<Integer>> bins = merger.merge(clusters, 500);

        // 没有 size 极小的孤儿包：那个 id=480 的小簇应被并进 480 的包
        List<Integer> binWithOrphan = bins.stream().filter(b -> b.contains(480)).findFirst().orElseThrow();
        assertThat(binWithOrphan).hasSizeGreaterThan(1);
        assertThat(bins).noneMatch(b -> b.size() == 1);
    }

    @Test
    void everyMemberAppearsExactlyOnce_andNoBinExceedsCapacity() {
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            clusters.add(new Cluster(List.of(i), i % 7, i / 7)); // 散在网格上
        }

        List<List<Integer>> bins = merger.merge(clusters, 6);

        assertThat(bins.stream().flatMap(List::stream).sorted().toList())
                .isEqualTo(java.util.stream.IntStream.range(0, 30).boxed().toList());
        assertThat(bins).allMatch(b -> b.size() <= 6 && !b.isEmpty());
    }

    @Test
    void clusterExceedsCapacity_throws() {
        List<Cluster> clusters = List.of(new Cluster(List.of(1, 2, 3), 0, 0));
        assertThatThrownBy(() -> merger.merge(clusters, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new LocalBestFitMortonMerger(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 生成 [start, start+count) 的连续 id 列表，用来模拟一个 count 大小的簇。 */
    private static List<Integer> members(int start, int count) {
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(start + i);
        return ids;
    }
}

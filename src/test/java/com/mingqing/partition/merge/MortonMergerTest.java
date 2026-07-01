package com.mingqing.partition.merge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MortonMergerTest {

    private final SpatialMerger merger = new MortonMerger();

    /**
     * 两团相距很远的簇，容量恰好=一团大小 → 每团各成一包，不会被拆混。
     * 这验证 Morton 排序确实把"地理上靠近的"排到了一起。
     *
     *   团 A: (0,0) (1,0) (0,1)          团 B: (100,100) (101,100) (100,101)
     */
    @Test
    void farApartBlobs_eachBecomesItsOwnBin() {
        List<Cluster> clusters = List.of(
                new Cluster(List.of(0), 0, 0),
                new Cluster(List.of(1), 1, 0),
                new Cluster(List.of(2), 0, 1),
                new Cluster(List.of(3), 100, 100),
                new Cluster(List.of(4), 101, 100),
                new Cluster(List.of(5), 100, 101)
        );

        List<List<Integer>> bins = merger.merge(clusters, 3);

        assertThat(bins).hasSize(2);
        assertThat(bins).anySatisfy(b -> assertThat(b).containsExactlyInAnyOrder(0, 1, 2));
        assertThat(bins).anySatisfy(b -> assertThat(b).containsExactlyInAnyOrder(3, 4, 5));
    }

    @Test
    void everyMemberAppearsExactlyOnce_andNoBinExceedsCapacity() {
        List<Cluster> clusters = List.of(
                new Cluster(List.of(0, 1), 0, 0),
                new Cluster(List.of(2), 5, 5),
                new Cluster(List.of(3, 4), 10, 1),
                new Cluster(List.of(5), 2, 9),
                new Cluster(List.of(6), 8, 8)
        );

        List<List<Integer>> bins = merger.merge(clusters, 3);

        assertThat(bins.stream().flatMap(List::stream).sorted().toList())
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(bins).allMatch(b -> b.size() <= 3 && !b.isEmpty());
    }

    @Test
    void emptyInput_yieldsNoBins() {
        assertThat(merger.merge(List.of(), 3)).isEmpty();
    }

    /**
     * 均衡装箱：10 个 size-1 簇、capacity=6。
     * 贪心填满会得 [6,4]（slack 堆在尾部）；均衡目标 target=⌈10/⌈10/6⌉⌉=5
     * 应得 [5,5]——同样 2 个包，但大小摊平，组间差从 2 降到 0。
     */
    @Test
    void balancedPacking_spreadsSlackEvenly() {
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            clusters.add(new Cluster(List.of(i), i, 0)); // 沿 x 轴排开，保证顺序确定
        }

        List<List<Integer>> bins = merger.merge(clusters, 6);

        assertThat(bins).hasSize(2);
        assertThat(bins).allMatch(b -> b.size() == 5);
    }

    /**
     * 回归测试：守住 spreadBits/morton 用 64 位 long 的设计（对全局顺序敏感）。
     * <p>
     * 沿 y 轴排 6 个单点（x=0），量程被 quantize 拉满，使高 y 越过 bit15 边界。
     * 正确的 long 版按 y 升序分组：点 0 与其空间邻居 1 同包、绝不与最远的 5 同包。
     * 若 spreadBits 退回 32 位 int，高 y 的码翻成负数被甩到最前，点 0 会与点 5
     * 错配——本测试随即失败。这正是之前 farApartBlobs（用 anySatisfy、对顺序不敏感）
     * 抓不到的盲区。
     */
    @Test
    void preservesYOrder_acrossSignBitBoundary() {
        List<Cluster> clusters = new ArrayList<>();
        for (int y = 0; y < 6; y++) {
            clusters.add(new Cluster(List.of(y), 0, y)); // 成员 id = y，便于断言
        }

        List<List<Integer>> bins = merger.merge(clusters, 2);

        List<Integer> binWith0 = bins.stream().filter(b -> b.contains(0)).findFirst().orElseThrow();
        assertThat(binWith0).contains(1).doesNotContain(5);
    }

    /** 契约与 SortSweepMerger 一致：单簇超容即上游 bug，fail-fast。 */
    @Test
    void clusterExceedsCapacity_throws() {
        List<Cluster> clusters = List.of(new Cluster(List.of(1, 2, 3), 0, 0)); // size 3 > cap 2

        assertThatThrownBy(() -> merger.merge(clusters, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

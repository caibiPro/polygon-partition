package com.mingqing.partition.merge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SortSweepMerger 的规格 + 示例集。
 * 全部用手写坐标，无需任何几何库——这正是把位置抽象成裸 double 的好处：
 * 你能像在草稿纸上画点一样，直接推演排序顺序和封箱时机。
 */
class SortSweepMergerTest {

    private final SpatialMerger merger = new SortSweepMerger();

    /**
     * 例1：4 个单成员簇排在 X 轴上，容量 2。
     *   x:   0    1    2    3
     *   id: [10] [11] [12] [13]
     * 按 x 排序后顺序装箱（cap=2）→ {10,11} | {12,13}
     */
    @Test
    void packsAlongXAxis() {
        List<Cluster> clusters = List.of(
                new Cluster(List.of(10), 0, 0),
                new Cluster(List.of(11), 1, 0),
                new Cluster(List.of(12), 2, 0),
                new Cluster(List.of(13), 3, 0)
        );

        List<List<Integer>> bins = merger.merge(clusters, 2);

        assertThat(bins).hasSize(2);
        assertThat(bins.get(0)).containsExactly(10, 11);
        assertThat(bins.get(1)).containsExactly(12, 13);
    }

    /**
     * 例2：多成员簇，验证"再加会超容就封箱"的边界。
     *   A=[1,2] x=0 (size2)，B=[3] x=1 (size1)，C=[4,5] x=2 (size2)，cap=3
     *   current=A(2) → +B=3≤3 收下 → current=[1,2,3]
     *   +C: 3+2=5>3 → 封箱[1,2,3]，新开 current=[4,5]
     *   结果：{1,2,3} | {4,5}
     */
    @Test
    void closesBinWhenNextClusterWouldOverflow() {
        List<Cluster> clusters = List.of(
                new Cluster(List.of(1, 2), 0, 0),
                new Cluster(List.of(3), 1, 0),
                new Cluster(List.of(4, 5), 2, 0)
        );

        List<List<Integer>> bins = merger.merge(clusters, 3);

        assertThat(bins).hasSize(2);
        assertThat(bins.get(0)).containsExactly(1, 2, 3);
        assertThat(bins.get(1)).containsExactly(4, 5);
    }

    /** 例3：X 相同，用 Y 打破平手 —— B(y=1) 应排在 A(y=5) 前。cap=1 → {2} | {1} */
    @Test
    void breaksXTiesByY() {
        List<Cluster> clusters = List.of(
                new Cluster(List.of(1), 0, 5),
                new Cluster(List.of(2), 0, 1)
        );

        List<List<Integer>> bins = merger.merge(clusters, 1);

        assertThat(bins).hasSize(2);
        assertThat(bins.get(0)).containsExactly(2);
        assertThat(bins.get(1)).containsExactly(1);
    }

    /** 不变量：每个成员出现且仅出现一次，且每个包 ≤ capacity。 */
    @Test
    void everyMemberAppearsExactlyOnce_andNoBinExceedsCapacity() {
        List<Cluster> clusters = List.of(
                new Cluster(List.of(0, 1), 3, 0),
                new Cluster(List.of(2), 1, 0),
                new Cluster(List.of(3, 4), 2, 0),
                new Cluster(List.of(5), 0, 0)
        );

        List<List<Integer>> bins = merger.merge(clusters, 3);

        assertThat(bins.stream().flatMap(List::stream).sorted().toList())
                .containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(bins).allMatch(b -> b.size() <= 3 && !b.isEmpty());
    }

    @Test
    void singleCluster_yieldsOneBin() {
        List<List<Integer>> bins = merger.merge(List.of(new Cluster(List.of(7), 0, 0)), 5);
        assertThat(bins).hasSize(1);
        assertThat(bins.get(0)).containsExactly(7);
    }
}

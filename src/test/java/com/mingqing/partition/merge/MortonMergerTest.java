package com.mingqing.partition.merge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}

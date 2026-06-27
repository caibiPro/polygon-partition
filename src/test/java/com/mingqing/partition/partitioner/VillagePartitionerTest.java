package com.mingqing.partition.partitioner;

import com.mingqing.partition.cut.TargetedPeelingStrategy;
import com.mingqing.partition.cut.algorithm.RecursivePeelingAlgorithm;
import com.mingqing.partition.domain.Plot;
import com.mingqing.partition.merge.MortonMerger;
import com.mingqing.partition.geometry.GeometryTestSupport;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VillagePartitionerTest {

    private VillagePartitioner partitioner(int maxGroupSize) {
        RecursivePeelingAlgorithm algorithm =
                new RecursivePeelingAlgorithm(new TargetedPeelingStrategy());
        return new VillagePartitioner(algorithm, new MortonMerger(), maxGroupSize);
    }

    /** 构造一行 n 个 10×10 方块，相邻共享边长 10，整体是一个连通分量。 */
    private List<Plot> rowOfPlots(int n) {
        List<Plot> plots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int x0 = i * 10;
            Geometry g = GeometryTestSupport.wkt(String.format(
                    "POLYGON ((%d 0, %d 0, %d 10, %d 10, %d 0))",
                    x0, x0 + 10, x0 + 10, x0, x0));
            plots.add(new Plot(i, "ref" + i, "V01", "村", "D01", "区", "市", g));
        }
        return plots;
    }

    @Test
    void everyPlotAppearsExactlyOnce() {
        List<Plot> plots = rowOfPlots(10);
        List<List<Plot>> groups = partitioner(4).partition(plots);

        List<Long> allIds = groups.stream().flatMap(List::stream).map(Plot::id).sorted().toList();
        assertThat(allIds).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void noGroupExceedsMaxGroupSize() {
        List<Plot> plots = rowOfPlots(10);
        List<List<Plot>> groups = partitioner(4).partition(plots);

        assertThat(groups).allMatch(g -> g.size() <= 4 && !g.isEmpty());
    }

    @Test
    void emptyInputYieldsNoGroups() {
        assertThat(partitioner(4).partition(List.of())).isEmpty();
    }
}

package com.mingqing.partition.cut;

import com.mingqing.partition.graph.WeightedEdge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PartitionCutterTest {

    // MST：0-1(10) — 1-2(2) — 2-3(8)，四节点，中间 1-2 最弱
    private static final List<WeightedEdge> MST = List.of(
            new WeightedEdge(0, 1, 10.0),
            new WeightedEdge(2, 3, 8.0),
            new WeightedEdge(1, 2, 2.0)
    );

    @Test
    void cut_intoTwoGroups_shouldReturnTwoGroups() {
        List<List<Integer>> groups = PartitionCutter.cut(4, MST, 2);

        // 保留 0-1(10) 和 2-3(8)，切掉 1-2(2)
        // 分组：{0,1} 和 {2,3}
        assertThat(groups).hasSize(2);
        assertThat(groups).allMatch(g -> g.size() == 2);
    }

    @Test
    void cut_intoFourGroups_shouldReturnFourSingletonGroups() {
        List<List<Integer>> groups = PartitionCutter.cut(4, MST, 4);

        assertThat(groups).hasSize(4);
        assertThat(groups).allMatch(g -> g.size() == 1);
    }

    @Test
    void cut_kEqualsOne_shouldReturnAllNodesInOneGroup() {
        List<List<Integer>> groups = PartitionCutter.cut(4, MST, 1);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).hasSize(4);
    }

    @Test
    void cut_nullMstEdges_shouldThrowException() {
        assertThatThrownBy(() -> PartitionCutter.cut(4, null, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.mingqing.partition.cut;

import com.mingqing.partition.cut.algorithm.GlobalWeakestEdgeAlgorithm;
import com.mingqing.partition.cut.algorithm.TreePartitionAlgorithm;
import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.graph.WeightedEdge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GlobalWeakestEdgeAlgorithmTest {

    private final TreePartitionAlgorithm algorithm
            = new GlobalWeakestEdgeAlgorithm();

    // MST：0-1(10) — 1-2(2) — 2-3(8)，四节点，中间 1-2 最弱
    private static final List<WeightedEdge> MST = List.of(
            new WeightedEdge(0, 1, 10.0),
            new WeightedEdge(2, 3, 8.0),
            new WeightedEdge(1, 2, 2.0)
    );

    @Test
    void cut_intoTwoGroups_shouldReturnTwoGroups() {
        PartitionContext context = new PartitionContext(0, 2);

        List<List<Integer>> groups = algorithm.partition(4, MST, context);

        // 保留 0-1(10) 和 2-3(8)，切掉 1-2(2)
        // 分组：{0,1} 和 {2,3}
        assertThat(groups).hasSize(2);
        assertThat(groups).allMatch(g -> g.size() == 2);
    }

    @Test
    void cut_intoFourGroups_shouldReturnFourSingletonGroups() {
        PartitionContext context = new PartitionContext(0, 4);

        List<List<Integer>> groups = algorithm.partition(4, MST, context);

        assertThat(groups).hasSize(4);
        assertThat(groups).allMatch(g -> g.size() == 1);
    }

    @Test
    void cut_kEqualsOne_shouldReturnAllNodesInOneGroup() {
        PartitionContext context = new PartitionContext(0, 1);

        List<List<Integer>> groups = algorithm.partition(4, MST, context);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).hasSize(4);
    }

    @Test
    void cut_nullMstEdges_shouldThrowException() {
        PartitionContext context = new PartitionContext(0, 2);

        assertThatThrownBy(() -> algorithm.partition(4, null, context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cut_nLessThanOne_shouldThrowException() {
        PartitionContext context = new PartitionContext(0, 1);

        assertThatThrownBy(() -> algorithm.partition(0, List.of(), context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cut_kLessThanOne_shouldThrowException() {
        PartitionContext context = new PartitionContext(0, 0);
        assertThatThrownBy(() -> algorithm.partition(4, MST, context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cut_kExceedsNodeCount_shouldThrowException() {
        PartitionContext context = new PartitionContext(0, 5); // n=4, k=5
        assertThatThrownBy(() -> algorithm.partition(4, MST, context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cut_twoEdgesWithSameWeight_cutsByInputOrder() {
        List<WeightedEdge> equalWeightMst = List.of(
                new WeightedEdge(0, 1, 5.0),
                new WeightedEdge(1, 2, 5.0),
                new WeightedEdge(2, 3, 10.0));

        List<List<Integer>> groups = algorithm.partition(4, equalWeightMst, new PartitionContext(0, 2));

        assertThat(groups).hasSize(2);
        // 稳定排序：0-1(5) 先于 1-2(5)，故保留 0-1，切断 1-2
        assertThat(groups).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(0, 1));
        assertThat(groups).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(2, 3));
    }
}

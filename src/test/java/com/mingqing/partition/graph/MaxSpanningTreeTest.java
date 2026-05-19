package com.mingqing.partition.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MaxSpanningTreeTest {

    @Test
    void compute_threeNodes_shouldReturnTwoEdges() {
        // 三角形：0-1(w=5), 1-2(w=3), 0-2(w=1)
        // MST 选权重最大的两条：0-1(5), 1-2(3)
        List<WeightedEdge> edges = List.of(
                new WeightedEdge(0, 1, 5.0),
                new WeightedEdge(1, 2, 3.0),
                new WeightedEdge(0, 2, 1.0)
        );

        List<WeightedEdge> mst = MaxSpanningTree.compute(3, edges);

        assertThat(mst).hasSize(2);
        assertThat(mst.stream().mapToDouble(WeightedEdge::weight).sum())
                .isCloseTo(8.0, within(1e-9));
    }

    @Test
    void compute_singleNode_shouldReturnEmpty() {
        List<WeightedEdge> mst = MaxSpanningTree.compute(1, List.of());

        assertThat(mst).isEmpty();
    }

    @Test
    void compute_disconnectedGraph_shouldThrowException() {
        // 节点 0-1 连通，节点 2 孤立
        List<WeightedEdge> edges = List.of(new WeightedEdge(0, 1, 10.0));

        assertThatThrownBy(() -> MaxSpanningTree.compute(3, edges))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compute_nullEdges_shouldThrowException() {
        assertThatThrownBy(() -> MaxSpanningTree.compute(3, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

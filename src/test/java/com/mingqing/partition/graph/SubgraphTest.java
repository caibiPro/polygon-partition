package com.mingqing.partition.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SubgraphTest {

    /**
     * 父图（global ID）：0-1-2-3 一条链 + 一条横跨的 0-2。
     * 取子集 {1, 2, 3} → local: 1→0, 2→1, 3→2。
     * 只有边 (1,2) 和 (2,3) 两端都在子集内；(0,1)/(0,2) 应被丢弃。
     */
    @Test
    void induce_keepsOnlyInternalEdges_andRelabelsToLocal() {
        List<WeightedEdge> parent = List.of(
                new WeightedEdge(0, 1, 5.0),
                new WeightedEdge(1, 2, 3.0),
                new WeightedEdge(2, 3, 7.0),
                new WeightedEdge(0, 2, 9.0)
        );

        List<WeightedEdge> sub = Subgraph.induce(List.of(1, 2, 3), parent);

        // (1,2,w=3) → (0,1,3)；(2,3,w=7) → (1,2,7)
        assertThat(sub)
                .extracting(WeightedEdge::u, WeightedEdge::v, WeightedEdge::weight)
                .containsExactlyInAnyOrder(
                        tuple(0, 1, 3.0),
                        tuple(1, 2, 7.0)
                );
    }

    @Test
    void induce_singleNode_yieldsNoEdges() {
        List<WeightedEdge> parent = List.of(new WeightedEdge(0, 1, 1.0));
        assertThat(Subgraph.induce(List.of(0), parent)).isEmpty();
    }
}

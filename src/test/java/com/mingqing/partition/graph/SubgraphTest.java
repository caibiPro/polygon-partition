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

    /**
     * 父邻接表（parent ID）：0↔1, 1↔2, 2↔3, 0↔2。
     * 取子集 {1, 2, 3} → local: 1→0, 2→1, 3→2。
     * 局部应只保留 0↔1（原 1↔2）和 1↔2（原 2↔3），跨界邻居被丢弃。
     */
    @Test
    void induceAdjacency_keepsOnlyInternalNeighbors_andRelabels() {
        List<List<Integer>> parentAdj = List.of(
                List.of(1, 2),     // 0 ↔ 1, 2
                List.of(0, 2),     // 1 ↔ 0, 2
                List.of(0, 1, 3),  // 2 ↔ 0, 1, 3
                List.of(2)         // 3 ↔ 2
        );

        List<List<Integer>> sub = Subgraph.induceAdjacency(List.of(1, 2, 3), parentAdj);

        assertThat(sub).hasSize(3);
        assertThat(sub.get(0)).containsExactlyInAnyOrder(1);    // local 0 (parent 1) ↔ local 1 (parent 2)
        assertThat(sub.get(1)).containsExactlyInAnyOrder(0, 2); // local 1 (parent 2) ↔ local 0, local 2
        assertThat(sub.get(2)).containsExactlyInAnyOrder(1);    // local 2 (parent 3) ↔ local 1 (parent 2)
    }
}

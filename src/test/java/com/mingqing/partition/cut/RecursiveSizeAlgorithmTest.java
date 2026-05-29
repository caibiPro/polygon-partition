package com.mingqing.partition.cut;

import com.mingqing.partition.cut.algorithm.RecursivePeelingAlgorithm;
import com.mingqing.partition.cut.algorithm.TreePartitionAlgorithm;
import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.graph.WeightedEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RecursiveSizeAlgorithmTest {

    private final TreePartitionAlgorithm algorithm =
            new RecursivePeelingAlgorithm(new TargetedPeelingStrategy());

    private static PartitionContext ctx(int maxGroupSize) {
        return new PartitionContext(maxGroupSize, 0);
    }

    @Test
    void partition_zeroMaxGroupSize_shouldThrow() {
        assertThatThrownBy(() -> algorithm.partition(3, List.of(), ctx(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partition_zeroNodes_shouldThrow() {
        assertThatThrownBy(() -> algorithm.partition(0, List.of(), ctx(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partition_nullMstEdges_shouldThrow() {
        assertThatThrownBy(() -> algorithm.partition(3, null, ctx(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partition_singleNode_returnsOneGroup() {
        List<List<Integer>> result = algorithm.partition(1, List.of(), ctx(5));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsExactly(0);
    }

    @Test
    void partition_allFitInOneGroup_returnsOneGroup() {
        // 链 0-1-2，maxGroupSize=5
        List<WeightedEdge> mst = List.of(
                new WeightedEdge(0, 1, 1), new WeightedEdge(1, 2, 1));

        List<List<Integer>> result = algorithm.partition(3, mst, ctx(5));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void partition_chainOf6_maxGroupSize2_produces3Groups() {
        List<WeightedEdge> mst = List.of(
                new WeightedEdge(0, 1, 1), new WeightedEdge(1, 2, 1),
                new WeightedEdge(2, 3, 1), new WeightedEdge(3, 4, 1),
                new WeightedEdge(4, 5, 1));

        List<List<Integer>> result = algorithm.partition(6, mst, ctx(2));

        assertThat(result).hasSize(3);
        result.forEach(g -> assertThat(g).hasSizeLessThanOrEqualTo(2));

        // 验证具体分组内容：链式图按 TargetedPeelingStrategy 剥离末端
        assertThat(result).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(4, 5));
        assertThat(result).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(2, 3));
        assertThat(result).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(0, 1));
    }

    @Test
    void partition_noGroupExceedsMaxGroupSize() {
        // 链 0..9，maxGroupSize=3
        List<WeightedEdge> mst = new ArrayList<>();
        for (int i = 0; i < 9; i++) mst.add(new WeightedEdge(i, i + 1, 1.0));

        List<List<Integer>> result = algorithm.partition(10, mst, ctx(3));

        result.forEach(g -> assertThat(g.size()).isLessThanOrEqualTo(3));
        List<Integer> allIds10 = result.stream().flatMap(List::stream).sorted().toList();
        assertThat(allIds10).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void partition_allNodesAccountedFor() {
        // 星形图：0 为中心，连接 1..6，maxGroupSize=3
        List<WeightedEdge> mst = new ArrayList<>();
        for (int i = 1; i <= 6; i++) mst.add(new WeightedEdge(0, i, 1.0));

        List<List<Integer>> result = algorithm.partition(7, mst, ctx(3));

        result.forEach(g -> assertThat(g.size()).isLessThanOrEqualTo(3));
        List<Integer> allIds7 = result.stream().flatMap(List::stream).sorted().toList();
        assertThat(allIds7).containsExactly(0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void partition_maxGroupSize1_everyNodeIsOwnGroup() {
        List<WeightedEdge> mst = List.of(
                new WeightedEdge(0, 1, 1), new WeightedEdge(1, 2, 1),
                new WeightedEdge(2, 3, 1));

        List<List<Integer>> result = algorithm.partition(4, mst, ctx(1));

        assertThat(result).hasSize(4);
        result.forEach(g -> assertThat(g).hasSize(1));
    }

    @Test
    void partition_maxGroupSizeEqualsN_singleGroup() {
        // 链 0-1-2-3-4，maxGroupSize=5 → 1 组
        List<WeightedEdge> mst = List.of(
                new WeightedEdge(0, 1, 1), new WeightedEdge(1, 2, 1),
                new WeightedEdge(2, 3, 1), new WeightedEdge(3, 4, 1));

        List<List<Integer>> result = algorithm.partition(5, mst, ctx(5));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).hasSize(5);
    }

    @Test
    void partition_balancedBinaryTree_splitsCorrectly() {
        // 平衡二叉树: 0→(1,2), 1→(3,4), 2→(5,6)
        //        0
        //       / \
        //      1   2
        //     / \ / \
        //    3  4 5  6
        List<WeightedEdge> mst = List.of(
                new WeightedEdge(0, 1, 5), new WeightedEdge(0, 2, 5),
                new WeightedEdge(1, 3, 3), new WeightedEdge(1, 4, 3),
                new WeightedEdge(2, 5, 3), new WeightedEdge(2, 6, 3));

        List<List<Integer>> result = algorithm.partition(7, mst, ctx(3));

        assertThat(result).hasSize(3);
        result.forEach(g -> assertThat(g.size()).isLessThanOrEqualTo(3));
        int total = result.stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(7);

        // 验证具体分组内容：TargetedPeelingStrategy 优先剥离最大合格子树 (size=3)
        assertThat(result).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(1, 3, 4));
        assertThat(result).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(2, 5, 6));
        assertThat(result).anySatisfy(g -> assertThat(g).containsExactlyInAnyOrder(0));
    }

    @Test
    void partition_disconnectedGraph_fallsBackToSingleGroup() {
        // 空 MST = 不连通图（合约违反），验证不崩溃、graceful degradation
        List<List<Integer>> result = algorithm.partition(2, List.of(), ctx(1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsExactlyInAnyOrder(0, 1);
    }
}

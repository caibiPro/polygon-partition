package com.mingqing.partition.pipeline;

import com.mingqing.partition.cut.algorithm.GlobalWeakestEdgeAlgorithm;
import com.mingqing.partition.cut.algorithm.TreePartitionAlgorithm;
import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.geometry.GeometryTestSupport;
import com.mingqing.partition.graph.AdjacencyGraphBuilder;
import com.mingqing.partition.graph.MaxSpanningTree;
import com.mingqing.partition.graph.WeightedEdge;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：从图斑列表到分组结果。
 *
 * 场景：4 个图斑排成一行，中间两段共享边很短（弱连接），
 * 切 1 条最弱边后应得到 2 个组。
 *
 *  A(10×10) | B(10×10) || C(10×2) || D(10×10)
 *            ^            ^
 *         edge=10      edge=2（最弱，应被切断）
 *
 *  索引：A=0, B=1, C=2, D=3
 *  期望分组：{0,1} 和 {2,3}
 */
class PartitionPipelineTest {

    private static final List<Geometry> POLYGONS = List.of(
            GeometryTestSupport.wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"),       // A=0
            GeometryTestSupport.wkt("POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))"),    // B=1
            GeometryTestSupport.wkt("POLYGON ((20 4, 30 4, 30 6, 20 6, 20 4))"),      // C=2, 高度仅 2
            GeometryTestSupport.wkt("POLYGON ((30 0, 40 0, 40 10, 30 10, 30 0))")     // D=3
    );

    @Test
    void pipeline_fourPolygons_cutWeakestEdge_shouldReturnTwoGroups() {
        // Step 1: 已完成 —— 构建带权邻接图
        List<WeightedEdge> edges = AdjacencyGraphBuilder.build(POLYGONS);

        // Step 2: 最大生成树
        List<WeightedEdge> mstEdges = MaxSpanningTree.compute(POLYGONS.size(), edges);

        // Step 3: 切掉 k-1 条最弱边，得到 k 个分组
        TreePartitionAlgorithm algorithm = new GlobalWeakestEdgeAlgorithm();
        List<List<Integer>> groups = algorithm.partition(
                POLYGONS.size(), mstEdges, new PartitionContext(0, 2));

        // Step 4: 断言
        assertThat(groups).hasSize(2);
        assertThat(groups).allMatch(g -> !g.isEmpty());
        List<Integer> allNodes = groups.stream().flatMap(List::stream).sorted().toList();
        assertThat(allNodes).containsExactly(0, 1, 2, 3);
    }
}

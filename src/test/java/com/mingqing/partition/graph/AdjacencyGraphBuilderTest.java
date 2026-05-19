package com.mingqing.partition.graph;

import com.mingqing.partition.geometry.GeometryTestSupport;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AdjacencyGraphBuilderTest {

    @Test
    void build_twoAdjacentPolygons_shouldReturnOneEdge() {
        Geometry a = GeometryTestSupport.wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
        Geometry b = GeometryTestSupport.wkt("POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))");

        List<WeightedEdge> edges = AdjacencyGraphBuilder.build(List.of(a, b));

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).weight()).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void build_threePolygonsInLine_shouldReturnTwoEdges() {
        Geometry a = GeometryTestSupport.wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
        Geometry b = GeometryTestSupport.wkt("POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))");
        Geometry c = GeometryTestSupport.wkt("POLYGON ((20 0, 30 0, 30 10, 20 10, 20 0))");

        List<WeightedEdge> edges = AdjacencyGraphBuilder.build(List.of(a, b, c));

        assertThat(edges).hasSize(2);
    }

    @Test
    void build_disjointPolygons_shouldReturnNoEdges() {
        Geometry a = GeometryTestSupport.wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
        Geometry b = GeometryTestSupport.wkt("POLYGON ((100 0, 110 0, 110 10, 100 10, 100 0))");

        List<WeightedEdge> edges = AdjacencyGraphBuilder.build(List.of(a, b));

        assertThat(edges).isEmpty();
    }

    @Test
    void build_nullInput_shouldThrowException() {
        assertThatThrownBy(() -> AdjacencyGraphBuilder.build(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

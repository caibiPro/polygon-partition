package com.mingqing.partition.geometry;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NeighborIndexTest {

    @Test
    void candidate_adjacentPolygon_shouldBeIncluded() {
        Geometry a = GeometryTestSupport.wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
        Geometry b = GeometryTestSupport.wkt("POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))");
        Geometry c = GeometryTestSupport.wkt("POLYGON ((100 0, 110 0, 110 10, 100 10, 100 0))");

        NeighborIndex index = new NeighborIndex(List.of(a, b, c));
        List<Geometry> result = index.candidates(a);

        assertThat(result).contains(b);
        assertThat(result).doesNotContain(c);
    }

    @Test
    void candidate_querySelf_shouldBeIncluded() {
        Geometry a = GeometryTestSupport.wkt("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");

        NeighborIndex index = new NeighborIndex(List.of(a));
        List<Geometry> result = index.candidates(a);

        assertThat(result).contains(a);
    }

    @Test
    void constructor_nullList_shouldThrowException() {
        assertThatThrownBy(() -> new NeighborIndex(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("polygons cannot be null");
    }
}

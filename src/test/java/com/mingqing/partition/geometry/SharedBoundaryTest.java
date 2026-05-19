package com.mingqing.partition.geometry;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import static org.assertj.core.api.Assertions.*;

public class SharedBoundaryTest {

    /** 两个矩形共享完整边 */
    @Test
    void sharedBoundaryLength_twoPolygonsShareFullEdge_shouldReturnSharedLength() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"
        );
        Geometry b = GeometryTestSupport.wkt(
                "POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))"
        );

        double length = GeometryOps.sharedBoundaryLength(a, b);
        assertThat(length).isCloseTo(10.0, within(1e-9));
    }

    /** 两个矩形只接触一个点 */
    @Test
    void sharedBoundaryLength_twoPolygonsTouchOnlyAtPoint_shouldReturnZero() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"
        );
        Geometry b = GeometryTestSupport.wkt(
                "POLYGON ((10 10, 20 10, 20 20, 10 20, 10 10))"
        );

        double length = GeometryOps.sharedBoundaryLength(a, b);
        assertThat(length).isCloseTo(0.0, within(1e-9));
    }

    /** 两个矩形完全分离 */
    @Test
    void sharedBoundaryLength_twoPolygonsAreDisjoint_shouldReturnZero() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"
        );
        Geometry b = GeometryTestSupport.wkt(
                "POLYGON ((20 0, 30 0, 30 10, 20 10, 20 0))"
        );

        double length = GeometryOps.sharedBoundaryLength(a, b);
        assertThat(length).isCloseTo(0.0, within(1e-9));
    }

    /** 两个矩形部分共享边 */
    @Test
    void sharedBoundaryLength_twoPolygonsPartialSharedBoundary_shouldReturnSharedLength() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))"
        );
        Geometry b = GeometryTestSupport.wkt(
                "POLYGON((10 3, 20 3, 20 7, 10 7, 10 3))"
        );

        double length = GeometryOps.sharedBoundaryLength(a, b);
        assertThat(length).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void sharedBoundaryLength_sameEdgeWithDifferentSegmentation_shouldReturnFullLength() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 5, 10 10, 0 10, 0 0))"
        );
        Geometry b = GeometryTestSupport.wkt(
                "POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))"
        );

        double length = GeometryOps.sharedBoundaryLength(a, b);
        assertThat(length).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void sharedBoundaryLength_samePolygon_shouldReturnBoundaryLength() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"
        );
        Geometry b = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"
        );

        double length = GeometryOps.sharedBoundaryLength(a, b);
        assertThat(length).isCloseTo(40.0, within(1e-9));
    }

    @Test
    void sharedBoundaryLength_nullGeometry_showThrowException() {
        Geometry a = GeometryTestSupport.wkt(
                "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))"
        );
        assertThatThrownBy(() -> GeometryOps.sharedBoundaryLength(a, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Geometry cannot be null");
    }

    @Test
    void sharedBoundaryLength_invalidPolygon_showThrowException() {
        Geometry invalid = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 10, 10 0, 0 10, 0 0))"
        );
         Geometry normal = GeometryTestSupport.wkt(
                "POLYGON ((10 0, 20 0, 20 10, 10 10, 10 0))"
        );

        assertThatThrownBy(() -> GeometryOps.sharedBoundaryLength(invalid, normal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Geometry is not valid");
    }

    @Test
    void sharedBoundaryLength_emptyPolygon_showReturnZero() {
        Geometry empty = GeometryTestSupport.wkt("POLYGON EMPTY");
        Geometry normal = GeometryTestSupport.wkt(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))"
        );

        double length = GeometryOps.sharedBoundaryLength(empty, normal);
        assertThat(length).isCloseTo(0.0, within(1e-9));
    }
}

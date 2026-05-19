package com.mingqing.partition.io;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapefileReaderTest {

    @Test
    void extractPolygons_singlePolygon_returnsOneElement() throws Exception {
        Geometry geom = new WKTReader().read(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))");
        List<Polygon> result = new ArrayList<>();

        ShapefileReader.extractPolygons(geom, result);

        assertThat(result).hasSize(1);
    }

    @Test
    void extractPolygons_multiPolygonWithTwoParts_returnsTwoElements() throws Exception {
        Geometry geom = new WKTReader().read(
                "MULTIPOLYGON(((0 0, 1 0, 1 1, 0 1, 0 0)), ((2 0, 3 0, 3 1, 2 1, 2 0)))");
        List<Polygon> result = new ArrayList<>();

        ShapefileReader.extractPolygons(geom, result);

        assertThat(result).hasSize(2);
    }

    @Test
    void extractPolygons_lineString_returnsEmpty() throws Exception {
        Geometry geom = new WKTReader().read(
                "LINESTRING(0 0, 1 1)");
        List<Polygon> result = new ArrayList<>();

        ShapefileReader.extractPolygons(geom, result);

        assertThat(result).isEmpty();
    }
}

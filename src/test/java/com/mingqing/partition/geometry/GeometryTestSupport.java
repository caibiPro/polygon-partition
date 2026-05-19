package com.mingqing.partition.geometry;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

public class GeometryTestSupport {

    private static final WKTReader READER = new WKTReader();

    public static Geometry wkt(String wkt) {
        try {
            return READER.read(wkt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

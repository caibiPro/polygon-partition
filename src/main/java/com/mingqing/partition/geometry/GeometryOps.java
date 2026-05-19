package com.mingqing.partition.geometry;

import org.locationtech.jts.geom.Geometry;

public class GeometryOps {

    private GeometryOps() {
    }

    public static double sharedBoundaryLength(Geometry a, Geometry b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Geometry cannot be null");
        }

        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }

        if (!a.isValid() || !b.isValid()) {
            throw new IllegalArgumentException("Geometry is not valid");
        }

        if (!a.getEnvelopeInternal().intersects(b.getEnvelopeInternal())) {
            return 0d;
        }

        Geometry shared = a.getBoundary().intersection(b.getBoundary());
        return shared.getLength();
    }
}

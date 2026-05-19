package com.mingqing.partition.domain;

import org.locationtech.jts.geom.Geometry;

public record Plot(
        long id,
        String referenceId,
        String villageCode,
        String villageName,
        String districtCode,
        String districtName,
        String cityName,
        Geometry geometry
) {}

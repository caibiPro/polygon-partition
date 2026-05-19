package com.mingqing.partition.mapper;

import com.mingqing.partition.domain.Plot;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;

public class SichuanPlotMapper implements PlotMapper {

    private static final String FIELD_ID = "OBJECTID";
    private static final String FIELD_REF_ID = "WYBH";
    private static final String FIELD_VILLAGE_CODE = "CUNDM";
    private static final String FIELD_VILLAGE_NAME = "CUNMC";
    private static final String FIELD_DISTRICT_CODE = "QXDM";
    private static final String FIELD_DISTRICT_NAME = "QXMC";
    private static final String FIELD_CITY_NAME = "DSMC";

    @Override
    public Plot map(SimpleFeature feature) {
        return new Plot(
                ((Number) feature.getAttribute(FIELD_ID)).longValue(),
                (String) feature.getAttribute(FIELD_REF_ID),
                (String) feature.getAttribute(FIELD_VILLAGE_CODE),
                (String) feature.getAttribute(FIELD_VILLAGE_NAME),
                (String) feature.getAttribute(FIELD_DISTRICT_CODE),
                (String) feature.getAttribute(FIELD_DISTRICT_NAME),
                (String) feature.getAttribute(FIELD_CITY_NAME),
                (Geometry) feature.getDefaultGeometry()
        );
    }
}

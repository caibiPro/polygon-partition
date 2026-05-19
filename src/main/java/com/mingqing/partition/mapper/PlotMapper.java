package com.mingqing.partition.mapper;

import com.mingqing.partition.domain.Plot;
import org.geotools.api.feature.simple.SimpleFeature;

public interface PlotMapper {
    Plot map(SimpleFeature feature);
}

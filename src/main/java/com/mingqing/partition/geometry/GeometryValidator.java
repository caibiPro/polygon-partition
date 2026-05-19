package com.mingqing.partition.geometry;

import com.mingqing.partition.domain.Plot;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

import java.util.ArrayList;
import java.util.List;

/**
 * 对图斑列表做几何有效性检查，返回无效图斑的 QC 报告。
 */
public class GeometryValidator {

    private GeometryValidator() {
    }

    /**
     * 检查所有图斑的几何体，返回无效图斑的报告列表。
     * 几何体有效或为 null 的图斑不出现在结果里。
     */
    public static List<QCReport> validate(List<Plot> plots) {
        if (plots == null) {
            throw new IllegalArgumentException("plots cannot be null");
        }

        List<QCReport> reports = new ArrayList<>();
        for (Plot plot : plots) {
            Geometry geometry = plot.geometry();
            if (geometry == null) {
                continue;
            }

            IsValidOp op = new IsValidOp(geometry);
            if (op.isValid()) {
                continue;
            }

            TopologyValidationError error = op.getValidationError();
            reports.add(new QCReport(
                    plot,
                    error.getMessage(),
                    error.getCoordinate().x,
                    error.getCoordinate().y));
        }

        return reports;
    }
}

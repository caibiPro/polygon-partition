package com.mingqing.partition.geometry;

import com.mingqing.partition.domain.Plot;

/**
 * 单个图斑的质量检查报告条目。
 *
 * @param plot            有问题的图斑（含完整属性）
 * @param validationError JTS 返回的具体原因，如 "Self-intersection"
 * @param problemX        出错坐标 X（经度）
 * @param problemY        出错坐标 Y（纬度）
 */
public record QCReport(
        Plot plot,
        String validationError,
        double problemX,
        double problemY
) {
    @Override
    public String toString() {
        return String.format(
                "[id=%d | %s | %s %s] %s @ (%.6f, %.6f)",
                plot.id(), plot.referenceId(),
                plot.districtName(), plot.villageName(),
                validationError, problemX, problemY
        );
    }
}

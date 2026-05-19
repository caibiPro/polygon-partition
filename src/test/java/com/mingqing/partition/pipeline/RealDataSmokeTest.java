package com.mingqing.partition.pipeline;

import com.mingqing.partition.domain.Plot;
import com.mingqing.partition.geometry.GeometryValidator;
import com.mingqing.partition.geometry.QCReport;
import com.mingqing.partition.io.ShapefileReader;
import com.mingqing.partition.mapper.PlotMapper;
import com.mingqing.partition.mapper.SichuanPlotMapper;
import org.geotools.api.feature.simple.SimpleFeature;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 真实数据冒烟测试。手动运行，不进 CI。
 * 目的：验证新 Plot-based pipeline 在真实四川农田数据上能跑通。
 */
@Disabled("manual only")
class RealDataSmokeTest {

    private static final String SHP_PATH =
            System.getProperty("user.home") + "/Documents/files/shapefiles/四川测试数据/地块数据.shp";

    @Test
    void smoke_realData_pipeline() throws Exception {
        // === Step 1: 读取 Shapefile → 映射为 Plot ===
        System.out.println("=== Step 1: 读取 Shapefile → Plot ===");
        long t0 = System.currentTimeMillis();
        List<SimpleFeature> features = ShapefileReader.readFeatures(new File(SHP_PATH));
        PlotMapper mapper = new SichuanPlotMapper();
        List<Plot> plots = features.stream().map(mapper::map).toList();
        System.out.printf("图斑总数：%d，耗时：%d ms%n", plots.size(), System.currentTimeMillis() - t0);

        // === Step 2: 几何有效性 QC ===
        System.out.println("\n=== Step 2: 几何有效性 QC ===");
        List<QCReport> qcReports = GeometryValidator.validate(plots);
        System.out.printf("无效图斑数：%d / %d%n", qcReports.size(), plots.size());
        qcReports.forEach(r -> System.out.println("  " + r));

        // === Step 3: 按村分组，统计村数量和图斑分布 ===
        System.out.println("\n=== Step 3: 按村分组 ===");
        Map<String, List<Plot>> plotsByVillage = plots.stream().collect(Collectors.groupingBy(Plot::villageCode));
        System.out.printf("村数量: %d%n", plotsByVillage.size());
        plotsByVillage.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(5)
                .forEach(e -> {
                    System.out.printf("  %s：%d 个图斑%n", e.getValue().get(0).villageName(), e.getValue().size());
                });
    }
}

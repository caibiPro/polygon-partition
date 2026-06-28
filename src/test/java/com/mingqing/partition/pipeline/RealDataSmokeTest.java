package com.mingqing.partition.pipeline;

import com.mingqing.partition.cut.TargetedPeelingStrategy;
import com.mingqing.partition.cut.algorithm.RecursivePeelingAlgorithm;
import com.mingqing.partition.domain.Plot;
import com.mingqing.partition.geometry.GeometryValidator;
import com.mingqing.partition.geometry.QCReport;
import com.mingqing.partition.io.ShapefileReader;
import com.mingqing.partition.mapper.PlotMapper;
import com.mingqing.partition.mapper.SichuanPlotMapper;
import com.mingqing.partition.merge.MortonMerger;
import com.mingqing.partition.merge.SortSweepMerger;
import com.mingqing.partition.merge.SpatialMerger;
import com.mingqing.partition.partitioner.VillagePartitioner;
import com.mingqing.partition.io.ShapefileWriter;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;

import java.io.File;
import java.util.ArrayList;
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
        // === Step 1: 流式读取 Shapefile → 直接 map 成 Plot（不持有全量 SimpleFeature）===
        System.out.println("=== Step 1: 读取 Shapefile → Plot ===");
        long t0 = System.currentTimeMillis();
        PlotMapper mapper = new SichuanPlotMapper();
        List<Plot> plots = new ArrayList<>();
        ShapefileReader.readFeatures(new File(SHP_PATH), f -> plots.add(mapper.map(f)));
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

        // === Step 4: 逐村划分任务包，对比两种合并策略 ===
        System.out.println("\n=== Step 4: VillagePartitioner（maxGroupSize=" + MAX_GROUP_SIZE + "）===");
        evaluate("Morton", new MortonMerger(), plotsByVillage);
        evaluate("SortSweep", new SortSweepMerger(), plotsByVillage);

        // === Step 5: 每个任务包导出一个独立 Shapefile ===
        System.out.println("\n=== Step 5: 导出 Shapefile（每包一个文件）===");
        List<List<Plot>> packages = partitionAll(new MortonMerger(), plotsByVillage);

        CoordinateReferenceSystem crs = ShapefileReader.readCrs(new File(SHP_PATH));
        File outDir = new File(System.getProperty("user.home"),
                "Documents/files/shapefiles/四川测试数据_输出/task_packages");

        ShapefileWriter.writePerPackage(outDir, packages, crs);

        // 回读校验：每个 pkg_*.shp 的 feature 数之和应等于图斑总数（不丢不重）
        File[] shpFiles = outDir.listFiles((d, n) -> n.startsWith("pkg_") && n.endsWith(".shp"));
        int fileCount = shpFiles == null ? 0 : shpFiles.length;
        int totalWritten = 0;
        if (shpFiles != null) {
            for (File f : shpFiles) {
                totalWritten += ShapefileReader.readFeatures(f).size();
            }
        }
        System.out.printf("已写出目录：%s%n", outDir.getAbsolutePath());
        System.out.printf("文件数：%d（期望 %d 个包）%s%n",
                fileCount, packages.size(), fileCount == packages.size() ? " OK" : " FAIL");
        System.out.printf("回读 feature 总数：%d（期望 %d）%s%n",
                totalWritten, plots.size(), totalWritten == plots.size() ? " OK" : " FAIL");
    }

    /** 用指定 merger 划分所有村，返回扁平的任务包列表。 */
    private List<List<Plot>> partitionAll(SpatialMerger merger, Map<String, List<Plot>> plotsByVillage) {
        VillagePartitioner partitioner = new VillagePartitioner(
                new RecursivePeelingAlgorithm(new TargetedPeelingStrategy()), merger, MAX_GROUP_SIZE);
        List<List<Plot>> all = new ArrayList<>();
        for (List<Plot> village : plotsByVillage.values()) {
            all.addAll(partitioner.partition(village));
        }
        return all;
    }

    private static final int MAX_GROUP_SIZE = 500;

    /**
     * 用指定 merger 对所有村划分，汇总并打印质量指标。
     */
    private void evaluate(String name, SpatialMerger merger, Map<String, List<Plot>> plotsByVillage) {
        VillagePartitioner partitioner = new VillagePartitioner(
                new RecursivePeelingAlgorithm(new TargetedPeelingStrategy()), merger, MAX_GROUP_SIZE);

        long t0 = System.currentTimeMillis();
        List<List<Plot>> allPackages = new java.util.ArrayList<>();
        int totalIn = 0;
        for (List<Plot> village : plotsByVillage.values()) {
            totalIn += village.size();
            allPackages.addAll(partitioner.partition(village));
        }
        long ms = System.currentTimeMillis() - t0;

        // 不变量校验：集合划分（不丢不重）+ 容量上限
        int totalOut = allPackages.stream().mapToInt(List::size).sum();
        long distinctIds = allPackages.stream().flatMap(List::stream).map(Plot::id).distinct().count();
        boolean partitionOk = (totalOut == totalIn) && (distinctIds == totalIn);
        boolean capacityOk = allPackages.stream().allMatch(p -> p.size() <= MAX_GROUP_SIZE);

        // 均衡指标
        int[] sizes = allPackages.stream().mapToInt(List::size).sorted().toArray();
        int min = sizes[0], max = sizes[sizes.length - 1];
        double mean = (double) totalOut / sizes.length;
        double std = Math.sqrt(java.util.Arrays.stream(sizes)
                .mapToDouble(s -> (s - mean) * (s - mean)).average().orElse(0));

        // 紧凑度指标（越小越紧凑）
        double compactness = allPackages.stream()
                .mapToDouble(RealDataSmokeTest::packageCompactness)
                .average().orElse(0);

        System.out.printf("%n[%s] 包数=%d  耗时=%d ms%n", name, allPackages.size(), ms);
        System.out.printf("  集合划分不变量: %s (in=%d out=%d distinct=%d)%n",
                partitionOk ? "OK" : "FAIL", totalIn, totalOut, distinctIds);
        System.out.printf("  容量上限 ≤%d: %s%n", MAX_GROUP_SIZE, capacityOk ? "OK" : "FAIL");
        System.out.printf("  包大小: min=%d max=%d mean=%.1f std=%.1f (max-min=%d)%n",
                min, max, mean, std, max - min);
        System.out.printf("  平均紧凑度: %.6f (度，越小越紧凑)%n", compactness);
    }

    /**
     * 一个任务包的紧凑度：成员质心到包质心的平均距离（越小越聚拢）。
     * 这是衡量"外业人员领到的包是否集中在一片区域"的核心指标。
     */
    private static double packageCompactness(List<Plot> pkg) {
        if (pkg.size() <= 1) return 0;
        List<Point> centroids = new ArrayList<>(pkg.size());

        // 包质心 = 各成员质心坐标的算术平均
        double cx = 0, cy = 0;
        for (Plot p : pkg) {
            Point c = p.geometry().getCentroid();
            centroids.add(c);
            cx += c.getX();
            cy += c.getY();
        }
        cx /= pkg.size();
        cy /= pkg.size();

        // 各成员质心到包质心的平均距离（越小越紧凑）
        double sumDist = 0;
        for (Point c : centroids) {
            sumDist += Math.hypot(c.getX() - cx, c.getY() - cy);
        }
        return sumDist / pkg.size();
    }
}

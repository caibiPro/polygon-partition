package com.geo.split;

import org.locationtech.jts.geom.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 两种方案的对比测试
 *
 * 测试维度：
 *   1. 正确性：拓扑安全、阈值满足、覆盖完整
 *   2. 均衡性：各区域图斑数的方差
 *   3. 性能：执行时间
 *   4. 切割质量：切边数（图论方法）、区域紧凑度
 */
public class VillageSplitComparisonRunner {

    private static final GeometryFactory gf = new GeometryFactory();

    // ================================================================
    // 测试数据生成
    // ================================================================

    /**
     * 生成规则网格图斑（带扰动，模拟真实地块）
     *
     * 为什么要加扰动：
     *   纯规则网格中，切割线恰好在格线上，永远不会有冲突
     *   加了扰动后，图斑边界不再对齐，会出现真实场景中的冲突
     *   这才能真正测试拓扑安全逻辑
     */
    static TestData generateTestData(int rows, int cols, double width, double height, long seed) {
        Random rng = new Random(seed);
        double cellW = width / cols;
        double cellH = height / rows;

        // 扰动网格点
        double[][] gx = new double[rows + 1][cols + 1];
        double[][] gy = new double[rows + 1][cols + 1];
        double perturbation = 0.2;

        for (int i = 0; i <= rows; i++) {
            for (int j = 0; j <= cols; j++) {
                gx[i][j] = j * cellW;
                gy[i][j] = i * cellH;
                if (i > 0 && i < rows && j > 0 && j < cols) {
                    gx[i][j] += (rng.nextDouble() - 0.5) * cellW * perturbation;
                    gy[i][j] += (rng.nextDouble() - 0.5) * cellH * perturbation;
                }
            }
        }

        // 村边界
        Polygon village = gf.createPolygon(new Coordinate[]{
            new Coordinate(0, 0), new Coordinate(width, 0),
            new Coordinate(width, height), new Coordinate(0, height),
            new Coordinate(0, 0)
        });

        // 图斑（留微小间隙模拟道路）
        double gap = cellW * 0.02;
        List<Geometry> parcels = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                try {
                    Polygon cell = gf.createPolygon(new Coordinate[]{
                        new Coordinate(gx[i][j] + gap, gy[i][j] + gap),
                        new Coordinate(gx[i][j + 1] - gap, gy[i][j + 1] + gap),
                        new Coordinate(gx[i + 1][j + 1] - gap, gy[i + 1][j + 1] - gap),
                        new Coordinate(gx[i + 1][j] + gap, gy[i + 1][j] - gap),
                        new Coordinate(gx[i][j] + gap, gy[i][j] + gap)
                    });
                    if (cell.isValid() && cell.getArea() > 0) {
                        parcels.add(cell);
                    }
                } catch (Exception e) {
                    // 无效几何，跳过
                }
            }
        }

        return new TestData(village, parcels);
    }

    /**
     * 生成共边密集的图斑（无间隙，图斑完全铺满区域）
     * 这是最难处理的场景：没有间隙意味着切割线必须精确走在图斑边界上
     */
    static TestData generateDenseData(int rows, int cols, double width, double height) {
        double cellW = width / cols;
        double cellH = height / rows;

        Polygon village = gf.createPolygon(new Coordinate[]{
            new Coordinate(0, 0), new Coordinate(width, 0),
            new Coordinate(width, height), new Coordinate(0, height),
            new Coordinate(0, 0)
        });

        List<Geometry> parcels = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                Polygon cell = gf.createPolygon(new Coordinate[]{
                    new Coordinate(j * cellW, i * cellH),
                    new Coordinate((j + 1) * cellW, i * cellH),
                    new Coordinate((j + 1) * cellW, (i + 1) * cellH),
                    new Coordinate(j * cellW, (i + 1) * cellH),
                    new Coordinate(j * cellW, i * cellH)
                });
                parcels.add(cell);
            }
        }

        return new TestData(village, parcels);
    }

    static class TestData {
        final Geometry village;
        final List<Geometry> parcels;
        TestData(Geometry v, List<Geometry> p) { village = v; parcels = p; }
    }

    // ================================================================
    // 对比测试
    // ================================================================

    static void runComparison(String label, TestData data, int threshold) {
        System.out.println("\n" + "=".repeat(70));
        System.out.printf("  %s  |  图斑数=%d  阈值=%d%n", label, data.parcels.size(), threshold);
        System.out.println("=".repeat(70));

        // ---- 方案A：坐标递归二分 ----
        System.out.println("\n--- 方案A：坐标递归二分 ---");
        TopologySafeBisectionSplitter coordSplitter = new TopologySafeBisectionSplitter();

        long t0 = System.currentTimeMillis();
        List<TopologySafeBisectionSplitter.SplitRegion> coordRegions =
            coordSplitter.split(data.village, data.parcels, threshold);
        long coordTime = System.currentTimeMillis() - t0;

        printResults("坐标二分", coordRegions, coordTime);
        List<String> coordValidation = coordSplitter.validate(
            data.village, data.parcels, coordRegions, threshold);
        System.out.println("  验证: " + coordValidation.get(0));
        if (coordValidation.size() > 1) {
            coordValidation.subList(1, Math.min(5, coordValidation.size()))
                .forEach(v -> System.out.println("    " + v));
        }

        // ---- 方案B：图论划分 ----
        System.out.println("\n--- 方案B：图论划分 ---");
        MultilevelGraphPartitionSplitter graphSplitter = new MultilevelGraphPartitionSplitter();

        t0 = System.currentTimeMillis();
        List<TopologySafeBisectionSplitter.SplitRegion> graphRegions =
            graphSplitter.split(data.village, data.parcels, threshold);
        long graphTime = System.currentTimeMillis() - t0;

        printResults("图论划分", graphRegions, graphTime);

        // 计算切边数
        MultilevelGraphPartitionSplitter.Graph g = graphSplitter.buildGraph(data.parcels);
        int[] graphAssignment = new int[data.parcels.size()];
        for (TopologySafeBisectionSplitter.SplitRegion r : graphRegions) {
            for (Geometry p : r.parcels) {
                int idx = data.parcels.indexOf(p);
                if (idx >= 0) graphAssignment[idx] = r.id;
            }
        }
        int cutCost = graphSplitter.cutEdgeCost(g, graphAssignment);
        int totalEdges = 0;
        for (int u = 0; u < g.n; u++) totalEdges += g.degree(u);
        totalEdges /= 2;
        System.out.printf("  切边代价: %d / %d 总边 (%.1f%%)%n",
            cutCost, totalEdges, 100.0 * cutCost / Math.max(1, totalEdges));
    }

    static void printResults(String name, List<TopologySafeBisectionSplitter.SplitRegion> regions, long ms) {
        int[] counts = regions.stream().mapToInt(r -> r.parcels.size()).toArray();
        int max = Arrays.stream(counts).max().orElse(0);
        int min = Arrays.stream(counts).min().orElse(0);
        double avg = Arrays.stream(counts).average().orElse(0);
        double balance = avg > 0 ? max / avg : 0;

        // 紧凑度
        double compactness = regions.stream()
            .mapToDouble(r -> {
                double a = r.boundary.getArea();
                double p = r.boundary.getLength();
                return p > 0 ? 4 * Math.PI * a / (p * p) : 0;
            })
            .average().orElse(0);

        System.out.printf("  耗时: %d ms%n", ms);
        System.out.printf("  子区域数: %d%n", regions.size());
        System.out.printf("  图斑数分布: min=%d, max=%d, avg=%.1f%n", min, max, avg);
        System.out.printf("  均衡度: %.3f (1.0=完美)%n", balance);
        System.out.printf("  平均紧凑度: %.3f (1.0=圆形)%n", compactness);

        // 打印各区域详情
        System.out.println("  各区域:");
        for (TopologySafeBisectionSplitter.SplitRegion r : regions) {
            System.out.printf("    #%d: %d parcels, area=%.1f%n",
                r.id, r.parcels.size(), r.boundary.getArea());
        }
    }

    // ================================================================
    // WKT输出（可粘贴到QGIS查看）
    // ================================================================

    static void outputWKT(String label, TestData data,
                           List<TopologySafeBisectionSplitter.SplitRegion> regions) {
        System.out.println("\n--- WKT Output: " + label + " ---");
        System.out.println("-- 村边界:");
        System.out.println(data.village.toText());
        System.out.println("-- 子区域:");
        for (TopologySafeBisectionSplitter.SplitRegion r : regions) {
            System.out.printf("-- Region#%d (%d parcels):%n", r.id, r.parcels.size());
            System.out.println(r.boundary.toText());
        }
    }

    // ================================================================
    // Main
    // ================================================================

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  村边界空间分区算法 — 方案对比测试       ║");
        System.out.println("╚══════════════════════════════════════════╝");

        // 测试1：小规模带扰动（验证正确性）
        runComparison("小规模扰动网格",
            generateTestData(8, 8, 100, 100, 42), 12);

        // 测试2：中等规模（验证性能）
        runComparison("中等规模扰动网格",
            generateTestData(20, 20, 1000, 1000, 42), 50);

        // 测试3：密集共边（最难场景）
        runComparison("密集共边（无间隙）",
            generateDenseData(10, 10, 100, 100), 20);

        // 测试4：极端长条形区域
        runComparison("长条形区域",
            generateTestData(3, 30, 900, 90, 42), 15);

        // 测试5：较大规模
        runComparison("较大规模",
            generateTestData(30, 30, 3000, 3000, 42), 100);

        // 性能阶梯测试
        System.out.println("\n\n" + "=".repeat(70));
        System.out.println("  性能阶梯测试");
        System.out.println("=".repeat(70));
        System.out.printf("%-10s %-10s %-15s %-15s%n", "图斑数", "阈值", "坐标二分(ms)", "图论划分(ms)");

        for (int dim : new int[]{10, 20, 30, 50}) {
            TestData data = generateTestData(dim, dim, 1000, 1000, 42);
            int n = data.parcels.size();
            int th = Math.max(10, n / 8);

            TopologySafeBisectionSplitter cs = new TopologySafeBisectionSplitter();
            long t0 = System.currentTimeMillis();
            cs.split(data.village, data.parcels, th);
            long coordMs = System.currentTimeMillis() - t0;

            MultilevelGraphPartitionSplitter gs = new MultilevelGraphPartitionSplitter();
            t0 = System.currentTimeMillis();
            gs.split(data.village, data.parcels, th);
            long graphMs = System.currentTimeMillis() - t0;

            System.out.printf("%-10d %-10d %-15d %-15d%n", n, th, coordMs, graphMs);
        }

        System.out.println("\n测试完成。");
    }
}

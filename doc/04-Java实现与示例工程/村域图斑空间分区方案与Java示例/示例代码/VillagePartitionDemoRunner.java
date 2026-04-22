package com.geo.split;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTWriter;

import java.util.*;

/**
 * 测试与验证类
 * 
 * 包含：
 * 1. 合成数据生成器（生成符合约束的模拟数据）
 * 2. 多组测试场景
 * 3. 性能基准测试
 * 4. 可视化输出（WKT格式，可导入QGIS查看）
 */
public class VillagePartitionDemoRunner {

    private static final GeometryFactory gf = new GeometryFactory();
    private static final WKTWriter wktWriter = new WKTWriter();

    // ========================================================================
    // 合成数据生成器
    // ========================================================================

    /**
     * 生成网格状的图斑数据
     * 
     * 将村边界内部填充为 rows × cols 的规则网格图斑
     * 图斑之间有微小间隙（模拟道路）
     *
     * @param villageWidth  村边界宽度
     * @param villageHeight 村边界高度
     * @param rows          网格行数
     * @param cols          网格列数
     * @param gap           图斑间隙（模拟道路宽度）
     * @return [0]=村边界, [1..n]=图斑
     */
    public static TestData generateGridParcels(double villageWidth, double villageHeight,
                                                int rows, int cols, double gap) {
        // 村边界：矩形
        Polygon village = gf.createPolygon(new Coordinate[]{
            new Coordinate(0, 0),
            new Coordinate(villageWidth, 0),
            new Coordinate(villageWidth, villageHeight),
            new Coordinate(0, villageHeight),
            new Coordinate(0, 0)
        });

        double cellWidth = (villageWidth - gap * (cols + 1)) / cols;
        double cellHeight = (villageHeight - gap * (rows + 1)) / rows;

        List<Geometry> parcels = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double x = gap + c * (cellWidth + gap);
                double y = gap + r * (cellHeight + gap);
                Polygon parcel = gf.createPolygon(new Coordinate[]{
                    new Coordinate(x, y),
                    new Coordinate(x + cellWidth, y),
                    new Coordinate(x + cellWidth, y + cellHeight),
                    new Coordinate(x, y + cellHeight),
                    new Coordinate(x, y)
                });
                parcels.add(parcel);
            }
        }

        return new TestData(village, parcels);
    }

    /**
     * 生成不规则图斑数据（更接近真实场景）
     * 
     * 使用扰动的网格 + 随机合并相邻图斑 来模拟不规则形状
     */
    public static TestData generateIrregularParcels(double villageWidth, double villageHeight,
                                                     int approxCount, long seed) {
        Random rand = new Random(seed);

        // 先生成稍多一些的网格
        int gridSize = (int) Math.ceil(Math.sqrt(approxCount * 1.5));
        double cellW = villageWidth / gridSize;
        double cellH = villageHeight / gridSize;

        // 村边界：带有一些不规则性的多边形
        Coordinate[] villageBoundary = generateIrregularBoundary(
            villageWidth, villageHeight, 20, rand);
        Polygon village = gf.createPolygon(villageBoundary);

        // 生成扰动网格的图斑
        List<Geometry> parcels = new ArrayList<>();
        double[][] gridX = new double[gridSize + 1][gridSize + 1];
        double[][] gridY = new double[gridSize + 1][gridSize + 1];

        // 初始化网格点并添加随机扰动
        double perturbation = 0.15;  // 扰动幅度（相对于cell大小）
        for (int i = 0; i <= gridSize; i++) {
            for (int j = 0; j <= gridSize; j++) {
                gridX[i][j] = j * cellW;
                gridY[i][j] = i * cellH;

                // 边界点不扰动
                if (i > 0 && i < gridSize && j > 0 && j < gridSize) {
                    gridX[i][j] += (rand.nextDouble() - 0.5) * cellW * perturbation;
                    gridY[i][j] += (rand.nextDouble() - 0.5) * cellH * perturbation;
                }
            }
        }

        // 创建图斑
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                try {
                    Polygon cell = gf.createPolygon(new Coordinate[]{
                        new Coordinate(gridX[i][j], gridY[i][j]),
                        new Coordinate(gridX[i][j + 1], gridY[i][j + 1]),
                        new Coordinate(gridX[i + 1][j + 1], gridY[i + 1][j + 1]),
                        new Coordinate(gridX[i + 1][j], gridY[i + 1][j]),
                        new Coordinate(gridX[i][j], gridY[i][j])
                    });

                    if (cell.isValid() && village.contains(cell)) {
                        parcels.add(cell);
                    } else if (cell.isValid()) {
                        // 与村边界求交
                        Geometry clipped = village.intersection(cell);
                        if (clipped instanceof Polygon && clipped.getArea() > cellW * cellH * 0.1) {
                            parcels.add(clipped);
                        }
                    }
                } catch (Exception e) {
                    // 忽略无效几何
                }
            }
        }

        // 如果图斑太多，随机合并一些相邻图斑
        while (parcels.size() > approxCount) {
            int idx = rand.nextInt(parcels.size() - 1);
            try {
                Geometry merged = parcels.get(idx).union(parcels.get(idx + 1));
                if (merged instanceof Polygon && merged.isValid()) {
                    parcels.set(idx, merged);
                    parcels.remove(idx + 1);
                }
            } catch (Exception e) {
                // skip
            }
        }

        return new TestData(village, parcels);
    }

    /**
     * 生成不规则的村边界
     */
    private static Coordinate[] generateIrregularBoundary(double w, double h,
                                                            int numPoints, Random rand) {
        List<Coordinate> coords = new ArrayList<>();
        double cx = w / 2, cy = h / 2;
        double rx = w / 2 * 0.95, ry = h / 2 * 0.95;

        for (int i = 0; i < numPoints; i++) {
            double angle = 2 * Math.PI * i / numPoints;
            double perturbR = 0.85 + rand.nextDouble() * 0.15;
            double x = cx + rx * perturbR * Math.cos(angle);
            double y = cy + ry * perturbR * Math.sin(angle);
            coords.add(new Coordinate(x, y));
        }
        coords.add(coords.get(0)); // 闭合

        return coords.toArray(new Coordinate[0]);
    }

    /**
     * 测试数据容器
     */
    public static class TestData {
        public final Geometry village;
        public final List<Geometry> parcels;

        public TestData(Geometry village, List<Geometry> parcels) {
            this.village = village;
            this.parcels = parcels;
        }
    }

    // ========================================================================
    // 测试用例
    // ========================================================================

    /**
     * 测试1：小规模网格（验证正确性）
     */
    public static void testSmallGrid() {
        System.out.println("=== Test 1: Small Grid (100 parcels, threshold=20) ===");

        TestData data = generateGridParcels(100, 100, 10, 10, 0.5);
        System.out.printf("Village area: %.2f, Parcels: %d%n",
            data.village.getArea(), data.parcels.size());

        SpatialBisectionVillageSplitter splitter = new SpatialBisectionVillageSplitter();
        long start = System.currentTimeMillis();
        List<SpatialBisectionVillageSplitter.SplitRegion> regions = splitter.split(data.village, data.parcels, 20);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Split into %d regions in %d ms%n", regions.size(), elapsed);
        for (SpatialBisectionVillageSplitter.SplitRegion r : regions) {
            System.out.println("  " + r);
        }

        // 评估指标
        SpatialBisectionVillageSplitter.SplitMetrics metrics = new SpatialBisectionVillageSplitter.SplitMetrics(regions);
        System.out.println(metrics);

        // 验证
        SpatialBisectionVillageSplitter.ValidationResult validation = splitter.validate(
            data.village, data.parcels, regions, 20);
        System.out.println(validation);

        System.out.println();
    }

    /**
     * 测试2：中等规模（验证性能）
     */
    public static void testMediumScale() {
        System.out.println("=== Test 2: Medium Scale (10000 parcels, threshold=500) ===");

        TestData data = generateGridParcels(1000, 1000, 100, 100, 0.5);
        System.out.printf("Village area: %.2f, Parcels: %d%n",
            data.village.getArea(), data.parcels.size());

        SpatialBisectionVillageSplitter splitter = new SpatialBisectionVillageSplitter();
        long start = System.currentTimeMillis();
        List<SpatialBisectionVillageSplitter.SplitRegion> regions = splitter.split(data.village, data.parcels, 500);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Split into %d regions in %d ms%n", regions.size(), elapsed);

        SpatialBisectionVillageSplitter.SplitMetrics metrics = new SpatialBisectionVillageSplitter.SplitMetrics(regions);
        System.out.println(metrics);

        SpatialBisectionVillageSplitter.ValidationResult validation = splitter.validate(
            data.village, data.parcels, regions, 500);
        System.out.println(validation);

        System.out.println();
    }

    /**
     * 测试3：不规则图斑
     */
    public static void testIrregular() {
        System.out.println("=== Test 3: Irregular Parcels (≈2000 parcels, threshold=200) ===");

        TestData data = generateIrregularParcels(500, 500, 2000, 42L);
        System.out.printf("Village area: %.2f, Parcels: %d%n",
            data.village.getArea(), data.parcels.size());

        SpatialBisectionVillageSplitter splitter = new SpatialBisectionVillageSplitter();
        long start = System.currentTimeMillis();
        List<SpatialBisectionVillageSplitter.SplitRegion> regions = splitter.split(data.village, data.parcels, 200);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Split into %d regions in %d ms%n", regions.size(), elapsed);

        SpatialBisectionVillageSplitter.SplitMetrics metrics = new SpatialBisectionVillageSplitter.SplitMetrics(regions);
        System.out.println(metrics);

        SpatialBisectionVillageSplitter.ValidationResult validation = splitter.validate(
            data.village, data.parcels, regions, 200);
        System.out.println(validation);

        System.out.println();
    }

    /**
     * 测试4：边界情况
     */
    public static void testEdgeCases() {
        System.out.println("=== Test 4: Edge Cases ===");

        SpatialBisectionVillageSplitter splitter = new SpatialBisectionVillageSplitter();

        // 4a: 图斑数 < 阈值
        System.out.println("4a: parcels < threshold");
        TestData data = generateGridParcels(100, 100, 3, 3, 1);
        List<SpatialBisectionVillageSplitter.SplitRegion> regions = splitter.split(data.village, data.parcels, 100);
        System.out.printf("  %d regions (should be 1)%n", regions.size());

        // 4b: 阈值=1
        System.out.println("4b: threshold=1");
        data = generateGridParcels(100, 100, 4, 4, 1);
        regions = splitter.split(data.village, data.parcels, 1);
        System.out.printf("  %d regions (should be 16)%n", regions.size());
        SpatialBisectionVillageSplitter.ValidationResult validation = splitter.validate(
            data.village, data.parcels, regions, 1);
        System.out.println("  " + validation);

        // 4c: 空图斑列表
        System.out.println("4c: empty parcels");
        Polygon emptyVillage = gf.createPolygon(new Coordinate[]{
            new Coordinate(0, 0), new Coordinate(100, 0),
            new Coordinate(100, 100), new Coordinate(0, 100),
            new Coordinate(0, 0)
        });
        regions = splitter.split(emptyVillage, new ArrayList<>(), 10);
        System.out.printf("  %d regions (should be 1)%n", regions.size());

        System.out.println();
    }

    /**
     * 测试5：输出WKT用于可视化
     */
    public static void testWktOutput() {
        System.out.println("=== Test 5: WKT Output (paste into QGIS for visualization) ===");

        TestData data = generateGridParcels(100, 100, 8, 8, 1);
        SpatialBisectionVillageSplitter splitter = new SpatialBisectionVillageSplitter();
        List<SpatialBisectionVillageSplitter.SplitRegion> regions = splitter.split(data.village, data.parcels, 10);

        System.out.println("-- Village boundary:");
        System.out.println(wktWriter.write(data.village));
        System.out.println();

        System.out.println("-- Split regions:");
        for (SpatialBisectionVillageSplitter.SplitRegion r : regions) {
            System.out.printf("-- Region#%d (%d parcels):%n", r.id, r.parcels.size());
            System.out.println(wktWriter.write(r.regionPolygon));
        }
        System.out.println();
    }

    /**
     * 性能基准测试
     */
    public static void benchmarkScaling() {
        System.out.println("=== Benchmark: Scaling Test ===");
        System.out.printf("%-12s %-12s %-12s %-12s %-12s%n",
            "Parcels", "Threshold", "Regions", "Time(ms)", "Balance");

        int[] sizes = {100, 500, 1000, 5000, 10000};
        SpatialBisectionVillageSplitter splitter = new SpatialBisectionVillageSplitter();

        for (int size : sizes) {
            int gridDim = (int) Math.ceil(Math.sqrt(size));
            TestData data = generateGridParcels(1000, 1000, gridDim, gridDim, 0.5);
            int actualSize = data.parcels.size();
            int threshold = Math.max(10, actualSize / 10);

            long start = System.currentTimeMillis();
            List<SpatialBisectionVillageSplitter.SplitRegion> regions = splitter.split(
                data.village, data.parcels, threshold);
            long elapsed = System.currentTimeMillis() - start;

            SpatialBisectionVillageSplitter.SplitMetrics metrics = new SpatialBisectionVillageSplitter.SplitMetrics(regions);

            System.out.printf("%-12d %-12d %-12d %-12d %-12.3f%n",
                actualSize, threshold, regions.size(), elapsed, metrics.balanceFactor);
        }
    }

    // ========================================================================
    // 主入口
    // ========================================================================

    public static void main(String[] args) {
        testSmallGrid();
        testMediumScale();
        testIrregular();
        testEdgeCases();
        testWktOutput();
        benchmarkScaling();
    }
}

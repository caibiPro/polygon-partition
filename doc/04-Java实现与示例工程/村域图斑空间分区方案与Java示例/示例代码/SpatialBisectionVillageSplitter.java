package com.geo.split;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.polygonize.Polygonizer;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 村边界空间分区算法 —— 坐标递归二分 + 图斑感知切割
 * 
 * 核心思路：
 * 1. 建立空间索引（STR-tree）加速邻接查询
 * 2. 递归二分：沿较长轴，按质心坐标排序，中位数处切分
 * 3. 图斑感知：切割线不穿过图斑，被穿过的图斑整体归入一侧
 * 4. 几何切割：用JTS的difference操作实现多边形切分
 */
public class SpatialBisectionVillageSplitter {

    private final GeometryFactory gf;
    
    /** 切割线向包围盒外延伸的缓冲系数 */
    private static final double EXTEND_FACTOR = 1.5;
    
    /** 判断两个图斑"共边"（而非仅共点）的长度阈值 */
    private static final double SHARED_EDGE_TOLERANCE = 1e-6;

    public SpatialBisectionVillageSplitter() {
        this.gf = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING));
    }

    public SpatialBisectionVillageSplitter(GeometryFactory gf) {
        this.gf = gf;
    }

    // ========================================================================
    // 核心数据结构
    // ========================================================================

    /**
     * 切分结果：一个子区域多边形 + 其包含的图斑列表
     */
    public static class SplitRegion {
        /** 子区域的边界多边形 */
        public final Geometry regionPolygon;
        /** 该子区域内的图斑集合 */
        public final List<Geometry> parcels;
        /** 子区域编号 */
        public final int id;

        public SplitRegion(int id, Geometry regionPolygon, List<Geometry> parcels) {
            this.id = id;
            this.regionPolygon = regionPolygon;
            this.parcels = parcels;
        }

        @Override
        public String toString() {
            return String.format("Region#%d: %d parcels, area=%.2f", 
                id, parcels.size(), regionPolygon.getArea());
        }
    }

    /**
     * 评估指标
     */
    public static class SplitMetrics {
        public final double balanceFactor;      // 均衡度 (1.0 = 完美)
        public final double avgCompactness;     // 平均紧凑度 (0~1)
        public final double areaBalanceFactor;  // 面积均衡度
        public final int regionCount;           // 子区域数
        public final int maxParcelsInRegion;    // 最大子区域图斑数
        public final int minParcelsInRegion;    // 最小子区域图斑数

        public SplitMetrics(List<SplitRegion> regions) {
            this.regionCount = regions.size();

            int[] counts = regions.stream().mapToInt(r -> r.parcels.size()).toArray();
            double[] areas = regions.stream().mapToDouble(r -> r.regionPolygon.getArea()).toArray();

            this.maxParcelsInRegion = Arrays.stream(counts).max().orElse(0);
            this.minParcelsInRegion = Arrays.stream(counts).min().orElse(0);

            double avgCount = Arrays.stream(counts).average().orElse(1);
            this.balanceFactor = maxParcelsInRegion / avgCount;

            double avgArea = Arrays.stream(areas).average().orElse(1);
            this.areaBalanceFactor = Arrays.stream(areas).max().orElse(1) / avgArea;

            // Polsby-Popper紧凑度
            double sumCompactness = 0;
            for (SplitRegion r : regions) {
                double area = r.regionPolygon.getArea();
                double perimeter = r.regionPolygon.getLength();
                if (perimeter > 0) {
                    sumCompactness += 4 * Math.PI * area / (perimeter * perimeter);
                }
            }
            this.avgCompactness = sumCompactness / regionCount;
        }

        @Override
        public String toString() {
            return String.format(
                "SplitMetrics{\n" +
                "  regionCount=%d\n" +
                "  parcelRange=[%d, %d]\n" +
                "  balanceFactor=%.3f (1.0=完美)\n" +
                "  avgCompactness=%.3f (1.0=圆形)\n" +
                "  areaBalanceFactor=%.3f\n" +
                "}",
                regionCount, minParcelsInRegion, maxParcelsInRegion,
                balanceFactor, avgCompactness, areaBalanceFactor
            );
        }
    }

    // ========================================================================
    // 主入口方法
    // ========================================================================

    /**
     * 切分村边界
     *
     * @param villageBoundary 村边界多边形
     * @param parcels         所有图斑
     * @param threshold       每个子区域的最大图斑数
     * @return 切分后的子区域列表
     */
    public List<SplitRegion> split(Geometry villageBoundary, List<Geometry> parcels, int threshold) {
        if (parcels == null || parcels.isEmpty()) {
            return Collections.singletonList(new SplitRegion(0, villageBoundary, parcels));
        }
        if (parcels.size() <= threshold) {
            return Collections.singletonList(new SplitRegion(0, villageBoundary, parcels));
        }

        // 建立空间索引
        STRtree spatialIndex = buildSpatialIndex(parcels);

        List<SplitRegion> results = new ArrayList<>();
        int[] idCounter = {0};  // 用数组传引用

        recursiveSplit(villageBoundary, parcels, threshold, spatialIndex, results, idCounter, 0);

        return results;
    }

    // ========================================================================
    // 核心递归二分
    // ========================================================================

    /**
     * 递归切分
     *
     * @param region       当前区域多边形
     * @param parcels      当前区域内的图斑
     * @param threshold    阈值
     * @param globalIndex  全局空间索引（用于冲突检测）
     * @param results      结果收集器
     * @param idCounter    ID计数器
     * @param depth        递归深度（防止无限递归）
     */
    private void recursiveSplit(Geometry region, List<Geometry> parcels, int threshold,
                                 STRtree globalIndex, List<SplitRegion> results,
                                 int[] idCounter, int depth) {
        // 终止条件
        if (parcels.size() <= threshold || depth > 30) {
            results.add(new SplitRegion(idCounter[0]++, region, parcels));
            return;
        }

        // 1. 确定切割轴：选择包围盒较长的方向
        Envelope env = region.getEnvelopeInternal();
        boolean splitOnX = env.getWidth() >= env.getHeight();

        // 2. 按质心坐标排序
        List<Geometry> sorted = new ArrayList<>(parcels);
        sorted.sort((a, b) -> {
            Point ca = a.getCentroid();
            Point cb = b.getCentroid();
            double va = splitOnX ? ca.getX() : ca.getY();
            double vb = splitOnX ? cb.getX() : cb.getY();
            return Double.compare(va, vb);
        });

        // 3. 寻找最佳切分位置
        //    从中位数附近搜索，找到一个不穿过任何图斑的切割位置
        int mid = sorted.size() / 2;
        SplitResult splitResult = findBestSplit(region, sorted, mid, splitOnX, globalIndex);

        if (splitResult == null) {
            // 无法找到有效切分，作为叶子节点
            results.add(new SplitRegion(idCounter[0]++, region, parcels));
            return;
        }

        // 4. 递归处理两个子区域
        recursiveSplit(splitResult.regionA, splitResult.parcelsA, threshold,
                       globalIndex, results, idCounter, depth + 1);
        recursiveSplit(splitResult.regionB, splitResult.parcelsB, threshold,
                       globalIndex, results, idCounter, depth + 1);
    }

    /**
     * 切分操作的中间结果
     */
    private static class SplitResult {
        Geometry regionA, regionB;
        List<Geometry> parcelsA, parcelsB;
    }

    /**
     * 在中位数附近搜索最佳切分位置
     * 
     * 策略：从中位数开始，向两侧交替搜索，找到第一个"安全"的切分位置
     * "安全"意味着切割线不穿过任何图斑
     */
    private SplitResult findBestSplit(Geometry region, List<Geometry> sorted,
                                       int midIndex, boolean splitOnX, STRtree globalIndex) {
        int n = sorted.size();
        
        // 搜索范围：中位数附近 ± 20% 的范围
        int searchRadius = Math.max(1, n / 5);
        int lo = Math.max(1, midIndex - searchRadius);
        int hi = Math.min(n - 1, midIndex + searchRadius);

        // 从中间向两侧扩展搜索
        for (int offset = 0; offset <= searchRadius; offset++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int idx = midIndex + sign * offset;
                if (idx < lo || idx >= hi) continue;

                SplitResult result = trySpitAt(region, sorted, idx, splitOnX);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * 尝试在sorted[index]和sorted[index+1]之间切分
     */
    private SplitResult trySpitAt(Geometry region, List<Geometry> sorted,
                                    int index, boolean splitOnX) {
        Geometry parcelLeft = sorted.get(index - 1);
        Geometry parcelRight = sorted.get(index);

        // 计算切分坐标值：两个相邻图斑质心之间的中点
        double leftVal = splitOnX ? parcelLeft.getCentroid().getX() : parcelLeft.getCentroid().getY();
        double rightVal = splitOnX ? parcelRight.getCentroid().getX() : parcelRight.getCentroid().getY();
        double splitValue = (leftVal + rightVal) / 2.0;

        // 构造切割线
        LineString cutLine = createCutLine(region.getEnvelopeInternal(), splitValue, splitOnX);

        // 检查切割线是否穿过任何图斑（不仅是相邻的两个，是所有的）
        boolean safecut = true;
        for (Geometry p : sorted) {
            if (cutLine.intersects(p) && !cutLine.touches(p)) {
                // 切割线穿过（而非仅接触）图斑的内部
                safecut = false;
                break;
            }
        }

        if (!safecut) {
            // 尝试调整：通过微移切割线来避开冲突
            // 这里使用简单策略：在左值和右值之间二分查找安全位置
            Double safeSplitValue = binarySearchSafePosition(sorted, splitOnX, leftVal, rightVal);
            if (safeSplitValue == null) {
                return null;
            }
            splitValue = safeSplitValue;
            cutLine = createCutLine(region.getEnvelopeInternal(), splitValue, splitOnX);
        }

        // 用切割线切分区域多边形
        Geometry[] splitRegions = splitPolygon(region, cutLine, splitOnX, splitValue);
        if (splitRegions == null || splitRegions.length != 2) {
            return null;
        }

        // 将图斑分配到两个子区域
        List<Geometry> parcelsA = new ArrayList<>();
        List<Geometry> parcelsB = new ArrayList<>();

        for (Geometry p : sorted) {
            double centroidVal = splitOnX ? p.getCentroid().getX() : p.getCentroid().getY();
            if (centroidVal < splitValue) {
                parcelsA.add(p);
            } else {
                parcelsB.add(p);
            }
        }

        // 验证两个子区域都非空
        if (parcelsA.isEmpty() || parcelsB.isEmpty()) {
            return null;
        }

        SplitResult result = new SplitResult();
        result.regionA = splitRegions[0];
        result.regionB = splitRegions[1];
        result.parcelsA = parcelsA;
        result.parcelsB = parcelsB;
        return result;
    }

    /**
     * 二分查找安全的切分坐标位置
     * 在[lo, hi]范围内找到一个不穿过任何图斑的位置
     */
    private Double binarySearchSafePosition(List<Geometry> sorted, boolean splitOnX,
                                              double lo, double hi) {
        // 收集所有图斑在切割轴上的投影区间
        List<double[]> intervals = new ArrayList<>();
        for (Geometry p : sorted) {
            Envelope env = p.getEnvelopeInternal();
            if (splitOnX) {
                intervals.add(new double[]{env.getMinX(), env.getMaxX()});
            } else {
                intervals.add(new double[]{env.getMinY(), env.getMaxY()});
            }
        }

        // 按区间起点排序
        intervals.sort((a, b) -> Double.compare(a[0], b[0]));

        // 合并重叠区间，找到间隙
        List<double[]> gaps = new ArrayList<>();
        double mergedEnd = Double.NEGATIVE_INFINITY;
        double mergedStart = Double.NEGATIVE_INFINITY;

        for (double[] interval : intervals) {
            if (interval[0] > mergedEnd + SHARED_EDGE_TOLERANCE) {
                if (mergedEnd > Double.NEGATIVE_INFINITY) {
                    gaps.add(new double[]{mergedEnd, interval[0]});
                }
                mergedStart = interval[0];
                mergedEnd = interval[1];
            } else {
                mergedEnd = Math.max(mergedEnd, interval[1]);
            }
        }

        // 在[lo, hi]范围内找最近中间位置的间隙
        double target = (lo + hi) / 2.0;
        Double bestGapCenter = null;
        double bestDistance = Double.MAX_VALUE;

        for (double[] gap : gaps) {
            if (gap[1] < lo || gap[0] > hi) continue;
            double gapCenter = (Math.max(gap[0], lo) + Math.min(gap[1], hi)) / 2.0;
            double distance = Math.abs(gapCenter - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestGapCenter = gapCenter;
            }
        }

        return bestGapCenter;
    }

    // ========================================================================
    // 几何操作工具方法
    // ========================================================================

    /**
     * 建立空间索引
     */
    private STRtree buildSpatialIndex(List<Geometry> parcels) {
        STRtree tree = new STRtree();
        for (int i = 0; i < parcels.size(); i++) {
            Geometry p = parcels.get(i);
            tree.insert(p.getEnvelopeInternal(), p);
        }
        tree.build();
        return tree;
    }

    /**
     * 创建切割线：一条足够长的直线，穿过整个区域
     *
     * @param env        区域包围盒
     * @param splitValue 切割轴上的坐标值
     * @param splitOnX   true=垂直线(固定X), false=水平线(固定Y)
     */
    private LineString createCutLine(Envelope env, double splitValue, boolean splitOnX) {
        double extend = Math.max(env.getWidth(), env.getHeight()) * EXTEND_FACTOR;
        Coordinate c1, c2;

        if (splitOnX) {
            // 垂直切割线
            c1 = new Coordinate(splitValue, env.getMinY() - extend);
            c2 = new Coordinate(splitValue, env.getMaxY() + extend);
        } else {
            // 水平切割线
            c1 = new Coordinate(env.getMinX() - extend, splitValue);
            c2 = new Coordinate(env.getMaxX() + extend, splitValue);
        }

        return gf.createLineString(new Coordinate[]{c1, c2});
    }

    /**
     * 用切割线将多边形切成两部分
     * 
     * 实现原理：
     * 构造切割线一侧的大矩形（半平面近似），
     * 用原多边形与半平面求交得到一侧，
     * 用原多边形差集半平面得到另一侧。
     */
    private Geometry[] splitPolygon(Geometry polygon, LineString cutLine,
                                     boolean splitOnX, double splitValue) {
        try {
            Envelope env = polygon.getEnvelopeInternal();
            double extend = Math.max(env.getWidth(), env.getHeight()) * EXTEND_FACTOR;

            Geometry halfPlaneA, halfPlaneB;

            if (splitOnX) {
                // 左侧半平面
                halfPlaneA = gf.createPolygon(new Coordinate[]{
                    new Coordinate(env.getMinX() - extend, env.getMinY() - extend),
                    new Coordinate(splitValue, env.getMinY() - extend),
                    new Coordinate(splitValue, env.getMaxY() + extend),
                    new Coordinate(env.getMinX() - extend, env.getMaxY() + extend),
                    new Coordinate(env.getMinX() - extend, env.getMinY() - extend)
                });
                // 右侧半平面
                halfPlaneB = gf.createPolygon(new Coordinate[]{
                    new Coordinate(splitValue, env.getMinY() - extend),
                    new Coordinate(env.getMaxX() + extend, env.getMinY() - extend),
                    new Coordinate(env.getMaxX() + extend, env.getMaxY() + extend),
                    new Coordinate(splitValue, env.getMaxY() + extend),
                    new Coordinate(splitValue, env.getMinY() - extend)
                });
            } else {
                // 下侧半平面
                halfPlaneA = gf.createPolygon(new Coordinate[]{
                    new Coordinate(env.getMinX() - extend, env.getMinY() - extend),
                    new Coordinate(env.getMaxX() + extend, env.getMinY() - extend),
                    new Coordinate(env.getMaxX() + extend, splitValue),
                    new Coordinate(env.getMinX() - extend, splitValue),
                    new Coordinate(env.getMinX() - extend, env.getMinY() - extend)
                });
                // 上侧半平面
                halfPlaneB = gf.createPolygon(new Coordinate[]{
                    new Coordinate(env.getMinX() - extend, splitValue),
                    new Coordinate(env.getMaxX() + extend, splitValue),
                    new Coordinate(env.getMaxX() + extend, env.getMaxY() + extend),
                    new Coordinate(env.getMinX() - extend, env.getMaxY() + extend),
                    new Coordinate(env.getMinX() - extend, splitValue)
                });
            }

            Geometry regionA = polygon.intersection(halfPlaneA);
            Geometry regionB = polygon.intersection(halfPlaneB);

            // 确保结果是有效的多边形
            regionA = extractPolygon(regionA);
            regionB = extractPolygon(regionB);

            if (regionA == null || regionB == null || 
                regionA.isEmpty() || regionB.isEmpty()) {
                return null;
            }

            return new Geometry[]{regionA, regionB};

        } catch (Exception e) {
            System.err.println("splitPolygon failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从Geometry结果中提取多边形
     * intersection可能返回GeometryCollection，需要提取出面积最大的Polygon部分
     */
    private Geometry extractPolygon(Geometry geom) {
        if (geom == null || geom.isEmpty()) return null;

        if (geom instanceof Polygon) {
            return geom;
        }
        if (geom instanceof MultiPolygon) {
            return geom;
        }

        // GeometryCollection: 提取所有Polygon部分
        List<Polygon> polygons = new ArrayList<>();
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            Geometry sub = geom.getGeometryN(i);
            if (sub instanceof Polygon) {
                polygons.add((Polygon) sub);
            }
        }

        if (polygons.isEmpty()) return null;
        if (polygons.size() == 1) return polygons.get(0);

        return gf.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    // ========================================================================
    // 邻接图构建（用于评估和高级方案）
    // ========================================================================

    /**
     * 构建图斑邻接图
     * 两个图斑的边界交集是线（而非仅点）时视为相邻
     *
     * @return adjacency[i] = 与图斑i相邻的图斑索引集合
     */
    public List<Set<Integer>> buildAdjacencyGraph(List<Geometry> parcels) {
        STRtree tree = new STRtree();
        for (int i = 0; i < parcels.size(); i++) {
            tree.insert(parcels.get(i).getEnvelopeInternal(), i);
        }
        tree.build();

        List<Set<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < parcels.size(); i++) {
            adjacency.add(new HashSet<>());
        }

        for (int i = 0; i < parcels.size(); i++) {
            Geometry pi = parcels.get(i);
            // 扩展包围盒查询候选邻居
            Envelope queryEnv = new Envelope(pi.getEnvelopeInternal());
            queryEnv.expandBy(SHARED_EDGE_TOLERANCE);

            @SuppressWarnings("unchecked")
            List<Integer> candidates = tree.query(queryEnv);

            for (int j : candidates) {
                if (j <= i) continue;  // 避免重复检查
                Geometry pj = parcels.get(j);

                // 检查两个图斑是否共边
                try {
                    Geometry intersection = pi.intersection(pj);
                    if (intersection.getDimension() >= 1) {
                        // 交集包含线段（不仅是点）→ 共边
                        adjacency.get(i).add(j);
                        adjacency.get(j).add(i);
                    }
                } catch (Exception e) {
                    // 拓扑异常，跳过
                }
            }
        }

        return adjacency;
    }

    // ========================================================================
    // 验证工具
    // ========================================================================

    /**
     * 验证切分结果的正确性
     */
    public static class ValidationResult {
        public boolean valid = true;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();

        public void addError(String msg) {
            valid = false;
            errors.add(msg);
        }

        public void addWarning(String msg) {
            warnings.add(msg);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(valid ? "VALID" : "INVALID").append("\n");
            for (String e : errors) sb.append("  ERROR: ").append(e).append("\n");
            for (String w : warnings) sb.append("  WARN: ").append(w).append("\n");
            return sb.toString();
        }
    }

    /**
     * 全面验证切分结果
     */
    public ValidationResult validate(Geometry villageBoundary, List<Geometry> parcels,
                                      List<SplitRegion> regions, int threshold) {
        ValidationResult result = new ValidationResult();

        // 1. 检查每个子区域的图斑数 ≤ 阈值
        for (SplitRegion r : regions) {
            if (r.parcels.size() > threshold) {
                result.addError(String.format("Region#%d has %d parcels > threshold %d",
                    r.id, r.parcels.size(), threshold));
            }
        }

        // 2. 检查所有图斑都被分配
        int totalParcels = regions.stream().mapToInt(r -> r.parcels.size()).sum();
        if (totalParcels != parcels.size()) {
            result.addError(String.format("Total assigned parcels %d != input %d",
                totalParcels, parcels.size()));
        }

        // 3. 检查子区域之间不重叠
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                try {
                    Geometry intersection = regions.get(i).regionPolygon
                        .intersection(regions.get(j).regionPolygon);
                    if (intersection.getDimension() >= 2) {
                        // 面积重叠（不仅是边界接触）
                        result.addError(String.format(
                            "Region#%d and Region#%d overlap (area=%.6f)",
                            regions.get(i).id, regions.get(j).id, intersection.getArea()));
                    }
                } catch (Exception e) {
                    result.addWarning("Overlap check failed for regions " + i + "," + j);
                }
            }
        }

        // 4. 检查子区域的并集 ≈ 原村边界
        try {
            List<Geometry> regionPolygons = regions.stream()
                .map(r -> r.regionPolygon)
                .collect(Collectors.toList());
            Geometry unionOfRegions = CascadedPolygonUnion.union(regionPolygons);
            double areaDiff = Math.abs(unionOfRegions.getArea() - villageBoundary.getArea());
            double relDiff = areaDiff / villageBoundary.getArea();
            if (relDiff > 0.01) {
                result.addError(String.format(
                    "Union of regions differs from village boundary: relative diff = %.4f", relDiff));
            }
        } catch (Exception e) {
            result.addWarning("Union area check failed: " + e.getMessage());
        }

        // 5. 检查没有图斑被切分
        for (SplitRegion r : regions) {
            for (Geometry p : r.parcels) {
                try {
                    if (!r.regionPolygon.contains(p.getCentroid())) {
                        result.addWarning(String.format(
                            "Parcel centroid not inside its assigned region #%d", r.id));
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        }

        // 6. 检查子区域多边形有效性
        for (SplitRegion r : regions) {
            if (!r.regionPolygon.isValid()) {
                result.addError(String.format("Region#%d polygon is invalid", r.id));
            }
        }

        return result;
    }
}

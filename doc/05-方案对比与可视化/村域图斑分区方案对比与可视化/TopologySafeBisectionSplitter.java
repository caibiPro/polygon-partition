package com.geo.split;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 方案一：拓扑安全的坐标递归二分法
 *
 * ====== 核心思想 ======
 * 递归地沿较长轴方向切分区域，每次切分保证：
 *   1. 切割线不穿过任何图斑（拓扑安全）
 *   2. 两侧图斑数尽量均等（均衡性）
 *   3. 切分后的子区域是合法多边形（几何合法）
 *
 * ====== 拓扑安全的实现 ======
 * 关键创新：不是先切再修，而是先找安全位置再切。
 *
 * 步骤：
 *   1. 将所有图斑投影到切割轴上，得到一系列区间 [min, max]
 *   2. 合并重叠区间，找到所有"间隙"（gap）
 *   3. 在最接近中位数的间隙中心放置切割线
 *   4. 间隙处切割，天然不穿过任何图斑
 *
 * 这等价于一维情形中"在线段间隙处切割"的直接二维推广。
 */
public class TopologySafeBisectionSplitter {

    private final GeometryFactory gf;

    /** 切割线超出包围盒的延伸因子 */
    private static final double EXTEND = 2.0;
    /** 合并投影区间时的容差（处理浮点误差和共边） */
    private static final double MERGE_TOL = 1e-6;
    /** 最大递归深度（安全阀） */
    private static final int MAX_DEPTH = 50;

    public TopologySafeBisectionSplitter() {
        this.gf = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING));
    }

    // ================================================================
    // 数据结构
    // ================================================================

    /** 切分结果 */
    public static class SplitRegion {
        public final int id;
        public final Geometry boundary;
        public final List<Geometry> parcels;

        public SplitRegion(int id, Geometry boundary, List<Geometry> parcels) {
            this.id = id;
            this.boundary = boundary;
            this.parcels = parcels;
        }
    }

    /** 一维投影区间 */
    private static class Interval implements Comparable<Interval> {
        final double lo, hi;
        final int parcelIndex;

        Interval(double lo, double hi, int parcelIndex) {
            this.lo = lo;
            this.hi = hi;
            this.parcelIndex = parcelIndex;
        }

        @Override
        public int compareTo(Interval o) {
            return Double.compare(this.lo, o.lo);
        }
    }

    // ================================================================
    // 主入口
    // ================================================================

    /**
     * 切分村边界
     * @param village   村边界多边形
     * @param parcels   所有图斑（必须被village包含）
     * @param threshold 每个子区域的最大图斑数
     * @return 切分后的子区域列表
     */
    public List<SplitRegion> split(Geometry village, List<Geometry> parcels, int threshold) {
        if (parcels == null || parcels.size() <= threshold) {
            return Collections.singletonList(new SplitRegion(0, village, parcels != null ? parcels : new ArrayList<>()));
        }

        List<SplitRegion> results = new ArrayList<>();
        int[] idGen = {0};
        recursiveSplit(village, parcels, threshold, results, idGen, 0);
        return results;
    }

    // ================================================================
    // 递归核心
    // ================================================================

    private void recursiveSplit(Geometry region, List<Geometry> parcels, int threshold,
                                 List<SplitRegion> results, int[] idGen, int depth) {
        // ---- 终止条件 ----
        if (parcels.size() <= threshold || depth >= MAX_DEPTH) {
            results.add(new SplitRegion(idGen[0]++, region, parcels));
            return;
        }

        // ---- Step 1: 选择切割轴 ----
        // 策略：选择包围盒较长的方向
        // 为什么：较长方向有更多的"空间"来放置切割线，更容易找到安全间隙
        Envelope env = region.getEnvelopeInternal();
        boolean useX = env.getWidth() >= env.getHeight();

        // ---- Step 2: 投影 + 找间隙 ----
        // 将每个图斑在切割轴上的投影（包围盒的min/max）计算出来
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < parcels.size(); i++) {
            Envelope pe = parcels.get(i).getEnvelopeInternal();
            double lo = useX ? pe.getMinX() : pe.getMinY();
            double hi = useX ? pe.getMaxX() : pe.getMaxY();
            intervals.add(new Interval(lo, hi, i));
        }

        // 计算理想切分位置（中位数质心坐标）
        double[] centroids = parcels.stream()
            .mapToDouble(p -> useX ? p.getCentroid().getX() : p.getCentroid().getY())
            .sorted()
            .toArray();
        double medianCentroid = centroids[centroids.length / 2];

        // 找安全间隙
        Double safePosition = findSafeGapPosition(intervals, medianCentroid);

        if (safePosition == null) {
            // 当前轴找不到安全间隙，尝试另一个轴
            useX = !useX;
            intervals.clear();
            for (int i = 0; i < parcels.size(); i++) {
                Envelope pe = parcels.get(i).getEnvelopeInternal();
                double lo = useX ? pe.getMinX() : pe.getMinY();
                double hi = useX ? pe.getMaxX() : pe.getMaxY();
                intervals.add(new Interval(lo, hi, i));
            }
            centroids = parcels.stream()
                .mapToDouble(p -> useX ? p.getCentroid().getX() : p.getCentroid().getY())
                .sorted()
                .toArray();
            medianCentroid = centroids[centroids.length / 2];
            safePosition = findSafeGapPosition(intervals, medianCentroid);
        }

        if (safePosition == null) {
            // 两个轴都找不到安全间隙 → 作为叶子节点
            // 这在实际中极罕见，意味着所有图斑在两个方向上都完全连续无间隙
            results.add(new SplitRegion(idGen[0]++, region, parcels));
            return;
        }

        final double splitValue = safePosition;
        final boolean finalUseX = useX;

        // ---- Step 3: 几何切割 ----
        Geometry[] halves = splitPolygonByStraightLine(region, splitValue, useX);
        if (halves == null) {
            results.add(new SplitRegion(idGen[0]++, region, parcels));
            return;
        }

        // ---- Step 4: 分配图斑 ----
        // 按质心坐标分配（因为切割线在安全间隙中，不穿过任何图斑，所以质心一定在某一侧）
        List<Geometry> parcelsA = new ArrayList<>();
        List<Geometry> parcelsB = new ArrayList<>();
        for (Geometry p : parcels) {
            double val = finalUseX ? p.getCentroid().getX() : p.getCentroid().getY();
            if (val < splitValue) {
                parcelsA.add(p);
            } else {
                parcelsB.add(p);
            }
        }

        // 安全检查：确保两侧都有图斑
        if (parcelsA.isEmpty() || parcelsB.isEmpty()) {
            results.add(new SplitRegion(idGen[0]++, region, parcels));
            return;
        }

        // ---- Step 5: 递归 ----
        recursiveSplit(halves[0], parcelsA, threshold, results, idGen, depth + 1);
        recursiveSplit(halves[1], parcelsB, threshold, results, idGen, depth + 1);
    }

    // ================================================================
    // 安全间隙查找（核心拓扑保护算法）
    // ================================================================

    /**
     * 在投影区间的间隙中，找到最接近目标位置的安全切割点
     *
     * 算法步骤：
     *   1. 按区间起点排序
     *   2. 扫描线合并重叠区间（即图斑的投影可能重叠，因为共边）
     *   3. 合并后的区间之间就是"间隙"
     *   4. 在所有间隙中，选择中心最接近目标值的那个
     *
     * 时间复杂度：O(N log N)（排序主导）
     * 空间复杂度：O(N)
     *
     * 为什么合并区间时要用容差MERGE_TOL：
     *   两个共边的图斑，它们的投影区间端点可能因浮点误差有微小间隙
     *   这个"假间隙"实际上位于图斑共边处，在这里切割是不安全的
     *   加入容差可以将这些假间隙合并掉
     *
     * @param intervals 所有图斑在切割轴上的投影区间
     * @param target    理想的切割位置（中位数）
     * @return 安全的切割坐标，如果找不到返回null
     */
    private Double findSafeGapPosition(List<Interval> intervals, double target) {
        if (intervals.isEmpty()) return null;

        // Step 1: 排序
        List<Interval> sorted = new ArrayList<>(intervals);
        Collections.sort(sorted);

        // Step 2: 合并重叠区间，同时收集间隙
        List<double[]> gaps = new ArrayList<>();
        double mergedLo = sorted.get(0).lo;
        double mergedHi = sorted.get(0).hi;

        for (int i = 1; i < sorted.size(); i++) {
            Interval cur = sorted.get(i);
            if (cur.lo <= mergedHi + MERGE_TOL) {
                // 重叠或相邻 → 合并
                mergedHi = Math.max(mergedHi, cur.hi);
            } else {
                // 间隙！
                gaps.add(new double[]{mergedHi, cur.lo});
                mergedLo = cur.lo;
                mergedHi = cur.hi;
            }
        }

        // Step 3: 在间隙中找最接近目标的位置
        Double bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (double[] gap : gaps) {
            // 间隙中心
            double center = (gap[0] + gap[1]) / 2.0;
            double dist = Math.abs(center - target);
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = center;
            }
        }

        return bestPos;
    }

    // ================================================================
    // 几何切割
    // ================================================================

    /**
     * 用一条直线将多边形切成两部分
     *
     * 实现原理：构造两个半平面矩形，分别与原多边形求交
     *
     * 为什么用半平面求交而不是JTS的split：
     *   JTS核心库没有直接的polygon.split(line)操作
     *   polygon.intersection(halfPlane) 是标准的、经过大量测试的操作
     *   半平面用一个足够大的矩形来近似，简单且稳健
     *
     * 为什么要延伸(EXTEND)：
     *   如果半平面刚好和多边形等大，边界处的浮点运算可能产生碎片
     *   延伸后半平面完全包裹多边形，避免边界问题
     */
    private Geometry[] splitPolygonByStraightLine(Geometry polygon, double splitValue, boolean useX) {
        try {
            Envelope env = polygon.getEnvelopeInternal();
            double ext = Math.max(env.getWidth(), env.getHeight()) * EXTEND;

            Geometry halfA, halfB;
            if (useX) {
                halfA = createBox(env.getMinX() - ext, env.getMinY() - ext, splitValue, env.getMaxY() + ext);
                halfB = createBox(splitValue, env.getMinY() - ext, env.getMaxX() + ext, env.getMaxY() + ext);
            } else {
                halfA = createBox(env.getMinX() - ext, env.getMinY() - ext, env.getMaxX() + ext, splitValue);
                halfB = createBox(env.getMinX() - ext, splitValue, env.getMaxX() + ext, env.getMaxY() + ext);
            }

            Geometry regionA = extractPolygonal(polygon.intersection(halfA));
            Geometry regionB = extractPolygonal(polygon.intersection(halfB));

            if (regionA == null || regionB == null || regionA.isEmpty() || regionB.isEmpty()) {
                return null;
            }
            return new Geometry[]{regionA, regionB};
        } catch (Exception e) {
            return null;
        }
    }

    private Geometry createBox(double minX, double minY, double maxX, double maxY) {
        return gf.createPolygon(new Coordinate[]{
            new Coordinate(minX, minY),
            new Coordinate(maxX, minY),
            new Coordinate(maxX, maxY),
            new Coordinate(minX, maxY),
            new Coordinate(minX, minY)
        });
    }

    /**
     * 从几何结果中提取多边形部分
     * intersection可能返回GeometryCollection（包含点、线、面的混合体）
     * 我们只需要面（Polygon/MultiPolygon）
     */
    private Geometry extractPolygonal(Geometry geom) {
        if (geom instanceof Polygon || geom instanceof MultiPolygon) return geom;
        if (geom instanceof GeometryCollection) {
            List<Polygon> polys = new ArrayList<>();
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                Geometry g = geom.getGeometryN(i);
                if (g instanceof Polygon) polys.add((Polygon) g);
            }
            if (polys.isEmpty()) return null;
            if (polys.size() == 1) return polys.get(0);
            return gf.createMultiPolygon(polys.toArray(new Polygon[0]));
        }
        return null;
    }

    // ================================================================
    // 验证与评估
    // ================================================================

    /**
     * 验证切分结果的完整性
     *
     * 检查项：
     *   1. 每个子区域的图斑数 ≤ 阈值
     *   2. 所有图斑都已分配（无遗漏）
     *   3. 子区域之间不重叠（只允许边界接触）
     *   4. 子区域的并集 ≈ 原村边界
     *   5. 每个图斑的质心确实在其分配的子区域内
     *   6. 每个子区域的几何是有效的
     */
    public List<String> validate(Geometry village, List<Geometry> parcels,
                                  List<SplitRegion> regions, int threshold) {
        List<String> issues = new ArrayList<>();

        // 检查1: 阈值
        for (SplitRegion r : regions) {
            if (r.parcels.size() > threshold) {
                issues.add(String.format("ERROR: Region#%d has %d parcels > threshold %d",
                    r.id, r.parcels.size(), threshold));
            }
        }

        // 检查2: 图斑总数
        int total = regions.stream().mapToInt(r -> r.parcels.size()).sum();
        if (total != parcels.size()) {
            issues.add(String.format("ERROR: Total parcels %d != expected %d", total, parcels.size()));
        }

        // 检查3: 区域不重叠（面积维度）
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                try {
                    Geometry inter = regions.get(i).boundary.intersection(regions.get(j).boundary);
                    if (inter.getDimension() >= 2 && inter.getArea() > 1e-8) {
                        issues.add(String.format("ERROR: Region#%d and #%d overlap, area=%.6f",
                            regions.get(i).id, regions.get(j).id, inter.getArea()));
                    }
                } catch (Exception e) {
                    issues.add("WARN: Overlap check failed: " + e.getMessage());
                }
            }
        }

        // 检查4: 覆盖完整性
        try {
            List<Geometry> boundaries = regions.stream().map(r -> r.boundary).collect(Collectors.toList());
            Geometry union = CascadedPolygonUnion.union(boundaries);
            double diff = Math.abs(union.getArea() - village.getArea()) / village.getArea();
            if (diff > 0.01) {
                issues.add(String.format("ERROR: Coverage mismatch: relative diff = %.4f", diff));
            }
        } catch (Exception e) {
            issues.add("WARN: Coverage check failed: " + e.getMessage());
        }

        // 检查5: 图斑质心包含性
        for (SplitRegion r : regions) {
            for (Geometry p : r.parcels) {
                if (!r.boundary.contains(p.getCentroid())) {
                    issues.add(String.format("WARN: Parcel centroid outside Region#%d", r.id));
                }
            }
        }

        // 检查6: 几何有效性
        for (SplitRegion r : regions) {
            if (!r.boundary.isValid()) {
                issues.add(String.format("ERROR: Region#%d has invalid geometry", r.id));
            }
        }

        if (issues.isEmpty()) {
            issues.add("ALL CHECKS PASSED");
        }
        return issues;
    }

    /**
     * 计算均衡度 (Balance Factor)
     * BF = max_count / avg_count
     * 1.0 = 完美均衡
     */
    public double balanceFactor(List<SplitRegion> regions) {
        int[] counts = regions.stream().mapToInt(r -> r.parcels.size()).toArray();
        double avg = Arrays.stream(counts).average().orElse(1);
        int max = Arrays.stream(counts).max().orElse(0);
        return max / avg;
    }

    /**
     * 计算平均紧凑度 (Polsby-Popper)
     * 1.0 = 完美圆形
     */
    public double avgCompactness(List<SplitRegion> regions) {
        return regions.stream()
            .mapToDouble(r -> {
                double a = r.boundary.getArea();
                double p = r.boundary.getLength();
                return p > 0 ? 4 * Math.PI * a / (p * p) : 0;
            })
            .average().orElse(0);
    }
}

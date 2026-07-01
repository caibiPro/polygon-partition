package com.mingqing.partition.io;

import com.mingqing.partition.domain.Plot;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务包结果写出为 Shapefile。
 *
 * <p>每个图斑写成一条 Feature，附带其所属任务包编号 {@code pkg_id}（全局唯一）。
 * 外业人员可在 QGIS 等工具里按 {@code pkg_id} 着色或筛选，领取自己的任务包。</p>
 *
 * <p>输出 schema：{@code the_geom}(MultiPolygon) / {@code plot_id}(Long) /
 * {@code village}(String) / {@code pkg_id}(Integer)。中文村名用 UTF-8 写入。</p>
 */
public class ShapefileWriter {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private ShapefileWriter() {
        // 工具类不允许实例化
    }

    /**
     * 把划分结果写成 Shapefile。
     *
     * @param outShp   输出 .shp 主文件路径（同名的 .dbf/.shx/.prj 会一并生成）
     * @param packages 任务包列表，下标即 pkg_id；每个包是一组 Plot
     * @param crs      坐标参考系（从源数据 featureType 取得，保证投影一致）
     * @throws IOException 写出失败时抛出
     */
    public static void write(File outShp, List<List<Plot>> packages, CoordinateReferenceSystem crs)
            throws IOException {
        if (outShp == null) throw new IllegalArgumentException("outShp must not be null");
        if (packages == null) throw new IllegalArgumentException("packages must not be null");

        SimpleFeatureType type = buildType(crs);
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(type);
        DefaultFeatureCollection features = new DefaultFeatureCollection();
        for (int pkgId = 0; pkgId < packages.size(); pkgId++) {
            addPackageFeatures(features, builder, packages.get(pkgId), pkgId);
        }
        writeCollection(outShp, type, features);
    }

    /**
     * 每个任务包写出一个独立 Shapefile：{@code pkg_000.shp}、{@code pkg_001.shp}……
     * 文件内保留该包的每个图斑（多 feature，各自边界）。外业人员一人领一个文件。
     *
     * <p>写出前会清理目录里上一次运行留下的同模式产物（{@code pkg_<编号>.*}），
     * 避免"上次包数更多"残留的高编号文件成为孤儿，污染目录和回读校验。
     * createSchema 只能按文件名覆盖同名文件，管不到多出来的旧文件。</p>
     *
     * @param outDir   输出目录（不存在会创建）
     * @param packages 任务包列表，下标即文件编号
     * @param crs      坐标参考系
     * @throws IOException 任一文件写出失败时抛出
     */
    public static void writePerPackage(File outDir, List<List<Plot>> packages, CoordinateReferenceSystem crs)
            throws IOException {
        if (outDir == null) throw new IllegalArgumentException("outDir must not be null");
        if (packages == null) throw new IllegalArgumentException("packages must not be null");
        outDir.mkdirs();
        cleanStalePackageFiles(outDir);

        SimpleFeatureType type = buildType(crs);
        // 文件名零填充宽度，按包数自适应（≥3 位），保证字典序与编号一致
        int width = Math.max(3, String.valueOf(Math.max(0, packages.size() - 1)).length());

        for (int pkgId = 0; pkgId < packages.size(); pkgId++) {
            SimpleFeatureBuilder builder = new SimpleFeatureBuilder(type);
            DefaultFeatureCollection features = new DefaultFeatureCollection();
            addPackageFeatures(features, builder, packages.get(pkgId), pkgId);

            File outShp = new File(outDir, String.format("pkg_%0" + width + "d.shp", pkgId));
            writeCollection(outShp, type, features);
        }
    }

    /**
     * 删除目录里上一次运行产生的 {@code pkg_<编号>.*} 伴生文件
     * （.shp/.shx/.dbf/.prj/.fix/.qix/.cpg）。
     * 只匹配"pkg_数字.扩展名"，不会误删用户放进来的其它文件。
     */
    private static void cleanStalePackageFiles(File outDir) throws IOException {
        File[] stale = outDir.listFiles((dir, name) -> name.matches("pkg_\\d+\\..+"));
        if (stale == null) return;
        for (File f : stale) {
            if (!f.delete()) {
                throw new IOException("无法删除残留文件：" + f.getAbsolutePath());
            }
        }
    }

    /** 把一个 FeatureCollection 写入指定 .shp（建库 → 事务写入 → 释放）。 */
    private static void writeCollection(File outShp, SimpleFeatureType type, DefaultFeatureCollection features)
            throws IOException {
        ShapefileDataStore store = createStore(outShp, type);
        try {
            String typeName = store.getTypeNames()[0];
            SimpleFeatureSource source = store.getFeatureSource(typeName);
            if (!(source instanceof SimpleFeatureStore featureStore)) {
                throw new IOException("数据源不可写：" + typeName);
            }

            Transaction tx = new DefaultTransaction("write");
            featureStore.setTransaction(tx);
            try {
                featureStore.addFeatures(features);
                tx.commit();
            } catch (IOException e) {
                tx.rollback();
                throw e;
            } finally {
                tx.close();
            }
        } finally {
            store.dispose();
        }
    }

    /** 构建输出 schema，并绑定 CRS。 */
    private static SimpleFeatureType buildType(CoordinateReferenceSystem crs) {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName("task_packages");
        builder.setCRS(crs);
        builder.add("the_geom", MultiPolygon.class);
        builder.add("plot_id", Long.class);
        builder.length(254).add("village", String.class);
        builder.add("pkg_id", Integer.class);
        return builder.buildFeatureType();
    }

    /** 创建 ShapefileDataStore 并写入 schema，字符集设为 UTF-8 以支持中文。 */
    private static ShapefileDataStore createStore(File outShp, SimpleFeatureType type) throws IOException {
        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> params = new HashMap<>();
        params.put("url", outShp.toURI().toURL());
        params.put("create spatial index", Boolean.TRUE);

        ShapefileDataStore store = (ShapefileDataStore) factory.createNewDataStore(params);
        store.createSchema(type);
        store.setCharset(StandardCharsets.UTF_8);
        return store;
    }

    /** 把一个包内的每个 Plot 转成 Feature 追加到 collection；pkg_id 由调用方给定。 */
    private static void addPackageFeatures(DefaultFeatureCollection collection,
                                           SimpleFeatureBuilder builder,
                                           List<Plot> plots,
                                           int pkgId) {
        for (Plot plot : plots) {
            builder.add(toMultiPolygon(plot.geometry()));
            builder.add(plot.id());
            builder.add(plot.villageName());
            builder.add(pkgId);
            // featureId 用 plot_id 保证唯一
            SimpleFeature feature = builder.buildFeature("plot." + plot.id());
            collection.add(feature);
        }
    }

    /** Shapefile 的面类型统一为 MultiPolygon；单 Polygon 包一层。 */
    private static MultiPolygon toMultiPolygon(Geometry geometry) {
        if (geometry instanceof MultiPolygon mp) {
            return mp;
        }
        if (geometry instanceof Polygon polygon) {
            return GEOMETRY_FACTORY.createMultiPolygon(new Polygon[]{polygon});
        }
        throw new IllegalArgumentException("不支持的几何类型：" + geometry.getGeometryType());
    }
}

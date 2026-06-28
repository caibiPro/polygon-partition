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

        ShapefileDataStore store = createStore(outShp, type);
        try {
            DefaultFeatureCollection features = buildFeatures(type, packages);

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

    /** 把每个包内的每个 Plot 转成 Feature，pkg_id = 包在列表中的下标。 */
    private static DefaultFeatureCollection buildFeatures(SimpleFeatureType type, List<List<Plot>> packages) {
        DefaultFeatureCollection collection = new DefaultFeatureCollection();
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(type);

        for (int pkgId = 0; pkgId < packages.size(); pkgId++) {
            for (Plot plot : packages.get(pkgId)) {
                builder.add(toMultiPolygon(plot.geometry()));
                builder.add(plot.id());
                builder.add(plot.villageName());
                builder.add(pkgId);
                // featureId 用 plot_id 保证唯一
                SimpleFeature feature = builder.buildFeature("plot." + plot.id());
                collection.add(feature);
            }
        }
        return collection;
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

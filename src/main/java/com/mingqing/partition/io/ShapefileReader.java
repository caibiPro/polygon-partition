package com.mingqing.partition.io;

import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shapefile 读取工具。
 *
 * <p>当前工具只提取面状几何，即 {@link Polygon} 和 {@link MultiPolygon}。
 * 如果源数据中存在 {@link MultiPolygon}，会被拆分为多个 {@link Polygon} 后返回。</p>
 *
 * <p>GeoTools 的 {@link FileDataStore} 不实现 {@link AutoCloseable}，不能直接使用
 * try-with-resources，因此本类在 finally 中显式调用 {@code dispose()}。
 * {@link SimpleFeatureIterator} 实现了可关闭接口，可以使用 try-with-resources 自动关闭。</p>
 */
public class ShapefileReader {

    private ShapefileReader() {
        // 工具类不允许实例化
    }

    /**
     * 读取 Shapefile 中的面状几何。
     *
     * <p>支持的数据类型：</p>
     * <ul>
     *     <li>{@link Polygon}：直接加入结果集。</li>
     *     <li>{@link MultiPolygon}：拆分为多个 {@link Polygon} 后加入结果集。</li>
     * </ul>
     *
     * <p>不处理 Point、LineString、MultiLineString 等非面状几何。
     * 如果某个 Feature 的默认几何为空，也会被跳过。</p>
     *
     * @param shpFile Shapefile 主文件，通常是以 .shp 结尾的文件
     * @return Shapefile 中提取出的 Polygon 列表；如果没有面状几何，返回空列表
     * @throws IOException 读取 Shapefile 失败时抛出
     * @throws IllegalArgumentException 当 {@code shpFile} 为空，或文件无法被 GeoTools 识别时抛出
     */
    public static List<Polygon> readShapefile(File shpFile) throws IOException {
        if (shpFile == null) {
            throw new IllegalArgumentException("Shapefile must not be null");
        }

        FileDataStore store = null;
        List<Polygon> polygons = new ArrayList<>();
        try {
            store = FileDataStoreFinder.getDataStore(shpFile);
            if (store == null) {
                throw new IllegalArgumentException("无法识别 Shapefile 文件：" + shpFile);
            }

            SimpleFeatureCollection collection = store.getFeatureSource().getFeatures();
            try (SimpleFeatureIterator iterator = collection.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Object geometry = feature.getDefaultGeometry();
                    if (!(geometry instanceof Geometry)) {
                        continue;
                    }

                    extractPolygons((Geometry) geometry, polygons);
                }
            }
        } finally {
            if (store != null) {
                store.dispose();
            }
        }

        return polygons;
    }

    /**
     * 读取 Shapefile 中的所有 Feature，保留几何体和属性。
     *
     * @param shpFile Shapefile 主文件
     * @return Feature 列表；如果文件为空，返回空列表
     * @throws IOException 读取失败时抛出
     * @throws IllegalArgumentException 当 shpFile 为 null 或无法识别时抛出
     */
    public static List<SimpleFeature> readFeatures(File shpFile) throws IOException {
        if (shpFile == null) {
            throw new IllegalArgumentException("Shapefile must not be null");
        }

        FileDataStore store = null;
        List<SimpleFeature> features = new ArrayList<>();
        try {
            store = FileDataStoreFinder.getDataStore(shpFile);
            if (store == null) {
                throw new IllegalArgumentException("无法识别 Shapefile 文件：" + shpFile);
            }

            SimpleFeatureCollection collection = store.getFeatureSource().getFeatures();
            try (SimpleFeatureIterator iterator = collection.features()) {
                while (iterator.hasNext()) {
                    features.add(iterator.next());
                }
            }
        } finally {
            if (store != null) {
                store.dispose();
            }
        }

        return features;
    }

    /**
     * 流式读取 Shapefile 的所有 Feature，逐个交给 {@code consumer} 处理，
     * 内部不累积成 List。
     *
     * <p>用于大数据量场景：调用方可在回调里立即把 {@link SimpleFeature} 映射成
     * 轻量域对象（如 Plot）并丢弃 feature，使其连带未用到的 dbf 属性被及时回收，
     * 避免"全量 SimpleFeature 常驻内存"。</p>
     *
     * @param shpFile  Shapefile 主文件
     * @param consumer 每个 Feature 的处理回调
     * @throws IOException 读取失败时抛出
     * @throws IllegalArgumentException 当参数为 null 或文件无法识别时抛出
     */
    public static void readFeatures(File shpFile, Consumer<SimpleFeature> consumer) throws IOException {
        if (shpFile == null) {
            throw new IllegalArgumentException("Shapefile must not be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }

        FileDataStore store = null;
        try {
            store = FileDataStoreFinder.getDataStore(shpFile);
            if (store == null) {
                throw new IllegalArgumentException("无法识别 Shapefile 文件：" + shpFile);
            }

            SimpleFeatureCollection collection = store.getFeatureSource().getFeatures();
            try (SimpleFeatureIterator iterator = collection.features()) {
                while (iterator.hasNext()) {
                    consumer.accept(iterator.next());
                }
            }
        } finally {
            if (store != null) {
                store.dispose();
            }
        }
    }

    /**
     * 只读取 Shapefile 的坐标参考系（CRS），不加载任何 Feature。
     *
     * <p>用于把"取 CRS"和"读全量 feature"解耦：写出结果时需要 CRS，但不必
     * 为此持有整份 feature 列表。</p>
     *
     * @param shpFile Shapefile 主文件
     * @return 该图层的 CRS；若 .prj 缺失可能为 null
     * @throws IOException 读取失败时抛出
     * @throws IllegalArgumentException 当 shpFile 为 null 或无法识别时抛出
     */
    public static CoordinateReferenceSystem readCrs(File shpFile) throws IOException {
        if (shpFile == null) {
            throw new IllegalArgumentException("Shapefile must not be null");
        }

        FileDataStore store = null;
        try {
            store = FileDataStoreFinder.getDataStore(shpFile);
            if (store == null) {
                throw new IllegalArgumentException("无法识别 Shapefile 文件：" + shpFile);
            }
            return store.getSchema().getCoordinateReferenceSystem();
        } finally {
            if (store != null) {
                store.dispose();
            }
        }
    }

    /**
     * 从单个 Geometry 中提取所有 Polygon，追加到 out。
     * MultiPolygon 展开为多个子 Polygon。
     * 其他类型（Point、LineString 等）直接忽略。
     */
    static void extractPolygons(Geometry geometry, List<Polygon> polygons) {
        if (geometry instanceof Polygon) {
            polygons.add((Polygon) geometry);
            return;
        }

        if (geometry instanceof MultiPolygon multiPolygon) {
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                Geometry part = multiPolygon.getGeometryN(i);
                if (part instanceof Polygon) {
                    polygons.add((Polygon) part);
                }
            }
        }
    }
}

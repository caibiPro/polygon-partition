package com.mingqing.partition.graph;

import com.mingqing.partition.geometry.GeometryTestSupport;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 学习实验：对比三种查找策略的性能与等值行为。
 * 不是业务测试，不跑 CI，仅用于观察和理解。
 */
class LookupStrategyExperimentTest {

    /**
     * 生成 n 个不重叠的 1×1 正方形图斑，排成一行。
     * 下标 i 对应坐标 x∈[i, i+1]，y∈[0, 1]。
     */
    private static List<Geometry> generatePolygons(int n) {
        List<Geometry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(GeometryTestSupport.wkt(
                    "POLYGON ((" + i + " 0, " + (i + 1) + " 0, " +
                    (i + 1) + " 1, " + i + " 1, " + i + " 0))"
            ));
        }
        return list;
    }

    /**
     * 实验一：性能对比
     *
     * 目标：用 5000 个图斑，对全部图斑依次做 "给定 Geometry，查它的下标" 操作，
     * 分别用三种方式计时，打印结果。
     *
     */
    @Test
    void performance_indexOf_vs_hashMap_vs_identityHashMap() {
        List<Geometry> geometries = generatePolygons(5000);
        Map<Geometry, Integer> map = new HashMap<>();
        Map<Geometry, Integer> identityMap = new IdentityHashMap<>();

        for (int i = 0; i < geometries.size(); i++) {
            Geometry geom = geometries.get(i);
            map.put(geom, i);
            identityMap.put(geom, i);
        }

        long indexSearchStart = System.nanoTime();
        for (Geometry geometry : geometries) {
            int index = geometries.indexOf(geometry);
        }
        long indexSearchDuration = System.nanoTime() - indexSearchStart;
        System.out.printf("IndexSearch - Total duration for 5000 items: %d ns%n", indexSearchDuration);


        long mapSearchStart = System.nanoTime();
        for (Geometry geom : geometries) {
            int index = map.getOrDefault(geom, -1);
        }
        long mapSearchDuration = System.nanoTime() - mapSearchStart;
        System.out.printf("MapSearch - Total duration for 5000 items: %d ns%n", mapSearchDuration);

        long idtMapSearchStart = System.nanoTime();
        for (Geometry geom : geometries) {
            int index = identityMap.getOrDefault(geom, -1);
        }
        long idtMapSearchDuration = System.nanoTime() - idtMapSearchStart;
        System.out.printf("IdtMapSearch - Total duration for 5000 items: %d ns%n", idtMapSearchDuration);

        assertThat(mapSearchDuration).isLessThan(indexSearchDuration);
        assertThat(idtMapSearchDuration).isLessThan(indexSearchDuration);
    }

    /**
     * 实验二：等值行为验证
     *
     * 目标：证明 JTS Geometry.equals() 是几何拓扑比较，
     * 而 == 是引用比较，两者对 Map 的行为影响完全不同。
     */
    @Test
    void equality_hashMap_vs_identityHashMap_behavior() {
        Geometry g1 = GeometryTestSupport.wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");
        Geometry g2 = GeometryTestSupport.wkt("POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))");

        assertThat(g1 == g2).isFalse();
        assertThat(g1.equals(g2)).isTrue();

        Map<Geometry, Integer> map = new HashMap<>();
        Map<Geometry, Integer> identityMap = new IdentityHashMap<>();
        map.put(g1, 0);
        identityMap.put(g1, 0);

        assertThat(map.containsKey(g2)).isTrue();
        assertThat(identityMap.containsKey(g2)).isFalse();
    }
}

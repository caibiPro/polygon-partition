package com.mingqing.partition.graph;

import com.mingqing.partition.geometry.GeometryOps;
import com.mingqing.partition.geometry.NeighborIndex;
import org.locationtech.jts.geom.Geometry;

import java.util.*;

public class AdjacencyGraphBuilder {

    private AdjacencyGraphBuilder() {
    }

    /**
     * 从图斑列表构建邻接图，返回所有真实相邻的边（共享边长 > 0）。
     * 节点编号 = 图斑在列表中的下标。
     */
    public static List<WeightedEdge> build(List<Geometry> polygons) {
        if (polygons == null) {
            throw new IllegalArgumentException("polygons cannot be null");
        }

        // 预检收集无效图斑
        Set<Integer> invalidIndices = new HashSet<>();
        for (int i = 0; i < polygons.size(); i++) {
            if (!polygons.get(i).isValid()) {
                invalidIndices.add(i);
            }
        }

        if (!invalidIndices.isEmpty()) {
            System.err.printf("[WARN] 发现 %d 个无效图斑，下标：%s%n",
                    invalidIndices.size(), invalidIndices);
        }

        List<WeightedEdge> result = new ArrayList<>();
        NeighborIndex index = new NeighborIndex(polygons);

        Map<Geometry, Integer> indexMap = new IdentityHashMap<>(polygons.size());
        for (int i = 0; i < polygons.size(); i++) {
            indexMap.put(polygons.get(i), i);
        }

        for (int i = 0; i < polygons.size(); i++) {
            if (invalidIndices.contains(i)) {
                continue;
            }

            Geometry geometry = polygons.get(i);
            List<Geometry> candidates = index.candidates(geometry);

            for (Geometry candidate : candidates) {
                Integer j = indexMap.get(candidate);
                if (j == null || j <= i || invalidIndices.contains(j)) {
                    continue;
                }

                double sharedLength = GeometryOps.sharedBoundaryLength(geometry, candidate);
                if (sharedLength > 0) {
                    result.add(new WeightedEdge(i, j, sharedLength));
                }
            }
        }

        return result;
    }
}

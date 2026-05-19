package com.mingqing.partition.geometry;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.List;

/**
 * 基于 STRtree 的空间索引，用于快速查询 envelope 可能相交的候选邻居。
 * 只负责候选过滤，不判断是否真正共享边。
 */
public class NeighborIndex {

    private final STRtree tree = new STRtree();

    public NeighborIndex(List<Geometry> polygons) {
        if (polygons == null) {
            throw new IllegalArgumentException("polygons cannot be null");
        }
        for (Geometry polygon : polygons) {
            tree.insert(polygon.getEnvelopeInternal(), polygon);
        }
    }

    /**
     * 返回与 query 的 envelope 相交的所有候选几何体（包含 query 自身）。
     */
    @SuppressWarnings("unchecked")
    public List<Geometry> candidates(Geometry query) {
        if (query == null) {
            throw new IllegalArgumentException("query geometry cannot be null");
        }

        Envelope queryEnvelope = query.getEnvelopeInternal();
        List<Geometry> queried = tree.query(queryEnvelope);

        // 使用引用检查 (Identity Check) 确保鲁棒性
        boolean containSelf = false;
        for (Geometry candidate : queried) {
            if (candidate == query) {
                containSelf = true;
                break;
            }
        }

        if (!containSelf) {
            queried.add(query);
        }

        return queried;
    }
}

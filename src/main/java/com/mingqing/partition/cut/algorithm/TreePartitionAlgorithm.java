package com.mingqing.partition.cut.algorithm;

import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.graph.WeightedEdge;

import java.util.List;

/**
 * 顶层图分割算法接口。
 */
public interface TreePartitionAlgorithm {

    /**
     * 执行分割。
     *
     * @param n        节点总数
     * @param mstEdges 最大生成树边集
     * @param context  分割约束参数
     * @return 分组结果 (Global ID 列表)
     */
    List<List<Integer>> partition(
            int n,
            List<WeightedEdge> mstEdges,
            PartitionContext context
    );
}

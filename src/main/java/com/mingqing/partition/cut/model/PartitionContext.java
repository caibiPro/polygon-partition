package com.mingqing.partition.cut.model;

/**
 * 图分割算法的全局配置上下文。
 *
 * @param maxGroupSize    递归尺寸算法需要的组内节点阈值数
 * @param targetGroupCount 全局 K-way 算法需要的目标分组数
 */
public record PartitionContext(
        int maxGroupSize,
        int targetGroupCount
) { }

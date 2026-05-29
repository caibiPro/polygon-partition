package com.mingqing.partition.cut;

import com.mingqing.partition.cut.model.TreeCutDecision;

/**
 * 树形图递归切分策略接口。
 */
public interface TreeCutStrategy {

    /**
     * 在当前局部子树中寻找最佳切点。
     *
     * @param parentArray  局部子树的父节点映射数组 (Local ID)
     * @param subtreeSize  局部子树各节点的子树大小
     * @param maxGroupSize 切分尺寸阈值 (部分策略可能忽略此参数)
     * @return 最佳切分决策。若无法切分（例如全树只剩根节点），则返回 null。
     */
    TreeCutDecision findBestCut(
            int[] parentArray,
            int[] subtreeSize,
            int maxGroupSize
    );
}

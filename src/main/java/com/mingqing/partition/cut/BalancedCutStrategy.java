package com.mingqing.partition.cut;

import com.mingqing.partition.cut.model.TreeCutDecision;

/**
 * 均衡切分策略。
 * 目标：寻找一条边，切断后两部分的大小差值 (imbalance) 最小。
 */
public class BalancedCutStrategy implements TreeCutStrategy {

    @Override
    public TreeCutDecision findBestCut(
            int[] parentArray,
            int[] subtreeSize,
            int maxGroupSize) {
        int bestChild = -1;
        int bestParent = -1;
        int minImbalance = Integer.MAX_VALUE;
        int totalNodes = parentArray.length;

        for (int i = 0; i < totalNodes; i++) {
            int parent = parentArray[i];
            if (parent < 0) {
                continue;
            }

            int subSize = subtreeSize[i];
            int anotherSubSize = totalNodes - subSize;
            int imbalance = Math.abs(subSize - anotherSubSize);

            if (imbalance < minImbalance) {
                minImbalance = imbalance;
                bestChild = i;
                bestParent = parent;
            }
        }

        if (bestChild == -1) {
            return null;
        }

        return new TreeCutDecision(bestChild, bestParent);
    }
}

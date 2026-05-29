package com.mingqing.partition.cut;

import com.mingqing.partition.cut.model.TreeCutDecision;

/**
 * 目标剥离策略。
 * 目标：寻找一个子树，其大小最接近 maxGroupSize 但不超标。
 */
public class TargetedPeelingStrategy implements TreeCutStrategy {

    @Override
    public TreeCutDecision findBestCut(
            int[] parentArray,
            int[] subtreeSize,
            int maxGroupSize) {
        // 未超标找最大值
        int bestValid = -1;
        int maxValidSize = -1;

        // 超标找最小值
        int bestOversized = -1;
        int minOversizedSize = Integer.MAX_VALUE;

        for (int i = 0; i < parentArray.length; i++) {
            int parent = parentArray[i];
            if (parent < 0) {
                continue;
            }

            int size = subtreeSize[i];
            if (size <= maxGroupSize) {
                if (size > maxValidSize) {
                    maxValidSize = size;
                    bestValid = i;
                }
            } else {
                if (size < minOversizedSize) {
                    minOversizedSize = size;
                    bestOversized = i;
                }
            }
        }

        int cutNode = bestValid != -1 ? bestValid : bestOversized;

        if (cutNode == -1) {
            return null;
        }

        return new TreeCutDecision(cutNode, parentArray[cutNode]);
    }
}

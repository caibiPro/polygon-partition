package com.mingqing.partition.cut;

import com.mingqing.partition.cut.model.TreeCutDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TargetedPeelingStrategyTest {

    private final TreeCutStrategy strategy = new TargetedPeelingStrategy();

    @Test
    void findBestCut_chainOf6_target2_picksSubtreeOfSize2() {
        // 链 0-1-2-3-4-5，root=0
        // subtreeSize: [6,5,4,3,2,1]，parentArray: [-1,0,1,2,3,4]
        // target=2，最接近且不超标的是 subtreeSize[4]=2
        int[] parent = {-1, 0, 1, 2, 3, 4};
        int[] size   = { 6, 5, 4, 3, 2, 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, 2);

        assertThat(decision).isNotNull();
        assertThat(decision.childId()).isEqualTo(4);
        assertThat(decision.parentId()).isEqualTo(3);
    }

    @Test
    void findBestCut_allSubtreesExceedTarget_picksSmallestOversized() {
        // parentArray: [-1, 0, 0]，subtreeSize: [3, 2, 2]，target=1
        // 两个候选都 > 1，选最小的（size=2 均可）
        int[] parent = {-1, 0, 0};
        int[] size   = { 3, 2, 2};

        TreeCutDecision decision = strategy.findBestCut(parent, size, 1);

        assertThat(decision).isNotNull();
        assertThat(decision.childId()).isIn(1, 2);
    }

    @Test
    void findBestCut_singleNode_returnsNull() {
        int[] parent = {-1};
        int[] size   = { 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, 5);

        assertThat(decision).isNull();
    }

    @Test
    void findBestCut_prefersLargestValidSubtree() {
        // subtreeSize[1]=3, subtreeSize[2]=1，target=4
        // 两者都 ≤ 4，应选最大的 subtreeSize[1]=3
        int[] parent = {-1, 0, 0};
        int[] size   = { 4, 3, 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, 4);

        assertThat(decision).isNotNull();
        assertThat(decision.childId()).isEqualTo(1);
    }

    @Test
    void findBestCut_tieOnSize_firstEncounteredWins() {
        // target=5，两个分支 size 都是 4（并列最大合格候选）
        // 策略用严格 >，所以先遍历到的（index=1）胜出，index=2 不满足 4 > 4
        int[] parent = {-1, 0, 0};
        int[] size   = { 5, 4, 4};

        TreeCutDecision decision = strategy.findBestCut(parent, size, 5);

        assertThat(decision.childId()).isEqualTo(1); // 低索引胜出，因为 > 是严格比较
    }

    @Test
    void findBestCut_targetExceedsAllBranchSizes_picksLargestBranch() {
        // 平衡二叉树：root=0，左子树 size=4，右子树 size=2
        // target=10 > 所有非根分支的 size，验证：不返回 null，选最大分支
        int[] parent = {-1, 0,  0,  1,  1,  2,  2};
        int[] size   = { 7, 4,  2,  2,  1,  1,  1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, 10);

        assertThat(decision).isNotNull();
        assertThat(decision.childId()).isEqualTo(1);   // size=4，最大合格候选
        assertThat(decision.parentId()).isEqualTo(0);
    }
}

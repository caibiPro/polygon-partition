package com.mingqing.partition.cut;

import com.mingqing.partition.cut.model.TreeCutDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BalancedCutStrategyTest {

    private final TreeCutStrategy strategy = new BalancedCutStrategy();

    private static int IGNORED_MAX_SIZE = 999;

    @Test
    void findBestCut_evenNodesChain_findsPerfectHalfCut() {
        // 偶数节点链表：0-1-2-3 (总数 4)
        // 完美的切法是切断 1-2，分成 {0,1} 和 {2,3}，大小都是 2，imbalance = 0
        int[] parent = {-1, 0, 1, 2};
        int[] size = {4, 3, 2, 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, IGNORED_MAX_SIZE);

        assertThat(decision).isNotNull();
        assertThat(decision.childId()).isEqualTo(2);
        assertThat(decision.parentId()).isEqualTo(1);
    }

    @Test
    void findBestCut_oddNodesChain_findsClosestHalfCutTieBreaking() {
        // 奇数节点链表：0-1-2-3-4 (总数 5)
        // 切法 A (切 2): subSize=3, another=2, imbalance=1
        // 切法 B (切 3): subSize=2, another=3, imbalance=1
        // 存在平局 (Tie)。由于源码使用的是 if (imbalance < minImbalance)，
        // 它应该确定性地选择最先遍历到的满足最小差值的节点，即节点 2 (如果 i 从小到大遍历)。
        int[] parent = {-1, 0, 1, 2, 3};
        int[] size = {5, 4, 3, 2, 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, IGNORED_MAX_SIZE);

        assertThat(decision).isNotNull();
        assertThat(decision.childId()).isEqualTo(2);
        assertThat(decision.parentId()).isEqualTo(1);
    }

    @Test
    void findBestCut_starGraph_picksFirstLeaf() {
        // 星形图：节点 0 为中心，1, 2, 3 为叶子 (总数 4)
        // 切任何一个叶子，都会分成 1 和 3，imbalance 都是 2。
        int[] parent = {-1, 0, 0, 0};
        int[] size   = {4, 1, 1, 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, IGNORED_MAX_SIZE);

        assertThat(decision).isNotNull();

        // 同样基于平局打破逻辑，应该选中数组中最先出现的非根节点，即节点 1
        assertThat(decision.childId()).isEqualTo(1);
        assertThat(decision.parentId()).isEqualTo(0);
    }

    @Test
    void findBestCut_singleNode_returnsNull() {
        // 只有一个节点，无法切分
        int[] parent = {-1};
        int[] size   = { 1};

        TreeCutDecision decision = strategy.findBestCut(parent, size, IGNORED_MAX_SIZE);

        assertThat(decision).isNull();
    }

    @Test
    void findBestCut_ignoresMaxGroupSizeParameter() {
        // 证明策略确实忽略了 maxGroupSize
        // 即使 maxGroupSize = 1，它依然会寻找最均衡的切分（切成 2 和 2），而不是试图找大小为 1 的块
        int[] parent = {-1, 0, 1, 2};
        int[] size   = { 4, 3, 2, 1};

        TreeCutDecision decisionWith1 = strategy.findBestCut(parent, size, 1);
        TreeCutDecision decisionWith99 = strategy.findBestCut(parent, size, 99);

        assertThat(decisionWith1).isNotNull();
        assertThat(decisionWith1.childId()).isEqualTo(2);

        assertThat(decisionWith99).isNotNull();
        assertThat(decisionWith99.childId()).isEqualTo(2);
    }


}

package com.mingqing.partition.cut.algorithm;

import com.mingqing.partition.cut.model.PartitionContext;
import com.mingqing.partition.cut.TreeCutStrategy;
import com.mingqing.partition.cut.model.TreeCutDecision;
import com.mingqing.partition.graph.Subgraph;
import com.mingqing.partition.graph.WeightedEdge;

import java.util.*;

/**
 * 递归尺寸切分算法 (Recursive Size-based Algorithm)。
 * 特征：采用分治递归，由局部微观策略 (TreeCutStrategy) 决定每一刀的位置，由 maxGroupSize 驱动。
 */
public class RecursivePeelingAlgorithm implements TreePartitionAlgorithm {
    private final TreeCutStrategy cutStrategy;

    public RecursivePeelingAlgorithm(TreeCutStrategy cutStrategy) {
        this.cutStrategy = cutStrategy;
    }

    /**
     * 对一棵连通的 MST 递归执行切分。
     *
     * @param n            Global ID 节点总数（节点编号 0..n-1）
     * @param mstEdges     MaxSpanningTree 产出的完整全局边集
     * @param context      切割配置（主要是获取控制递归结束的组节点阈值）
     * @return 分组结果列表，每个子列表包含一组划分好的 Global ID
     */
    @Override
    public List<List<Integer>> partition(
            int n,
            List<WeightedEdge> mstEdges,
            PartitionContext context) {

        int maxGroupSize = context.maxGroupSize();
        if (maxGroupSize <= 0) throw new IllegalArgumentException("maxGroupSize must be positive");
        if (n <= 0) throw new IllegalArgumentException("n must be positive");
        if (mstEdges == null) throw new IllegalArgumentException("mstEdges cannot be null");

        List<Integer> globalNodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) globalNodes.add(i);

        // 一次性将边集转为全局邻接表，后续递归复用，避免每层重复扫描全量边
        List<List<Integer>> globalAdj = buildGlobalAdjacency(n, mstEdges);

        List<List<Integer>> result = new ArrayList<>();
        split(globalNodes, globalAdj, maxGroupSize, result);
        return result;
    }

    /**
     * 递归切分核心（分治法流水线）。
     * <p>
     * 坐标系警告：此方法负责 Global ID 与 Local ID 的上下文切换。
     * 传递给下层工具方法的必须是 Local ID 构建的纯净图结构。
     *
     * @param globalNodes  当前待切分连通块的 Global ID 集合
     * @param globalAdj    完整的全局邻接表（一次性构建，贯穿所有递归层）
     * @param maxGroupSize 切分阈值
     * @param result       全局收集器，用于存放达标的分组
     */
    private void split(
            List<Integer> globalNodes,
            List<List<Integer>> globalAdj,
            int maxGroupSize,
            List<List<Integer>> result) {

        int localN = globalNodes.size();

        if (localN <= maxGroupSize) {
            result.add(globalNodes);
            return;
        }

        int[] subtreeSize = new int[localN];
        int[] parentArray = new int[localN];

        List<List<Integer>> localAdj = Subgraph.induceAdjacency(globalNodes, globalAdj);

        computeSubtreeSizes(0, localAdj, subtreeSize, parentArray);
        TreeCutDecision cut = cutStrategy.findBestCut(parentArray, subtreeSize, maxGroupSize);
        if (cut == null) {
            result.add(globalNodes);
            return;
        }

        List<Integer> localPeelPart = collectSubtreeNodes(cut.childId(), cut.parentId(), localAdj);
        boolean[] isPeeled = new boolean[localN];
        for (int localId : localPeelPart) {
            isPeeled[localId] = true;
        }

        List<Integer> globalPeelPart = new ArrayList<>(localPeelPart.size());
        List<Integer> globalRemainingPart = new ArrayList<>(localN - localPeelPart.size());

        for (int i = 0; i < localN; i++) {
            int globalId = globalNodes.get(i);
            if (isPeeled[i]) {
                globalPeelPart.add(globalId);
            } else {
                globalRemainingPart.add(globalId);
            }
        }

        split(globalPeelPart, globalAdj, maxGroupSize, result);
        split(globalRemainingPart, globalAdj, maxGroupSize, result);
    }

    /**
     * 将 MST 边集一次性转换为全局邻接表（只在 partition 入口调用一次）。
     */
    private static List<List<Integer>> buildGlobalAdjacency(int n, List<WeightedEdge> mstEdges) {
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (WeightedEdge e : mstEdges) {
            adj.get(e.u()).add(e.v());
            adj.get(e.v()).add(e.u());
        }
        return adj;
    }

    /**
     * 通过迭代 DFS 计算局部子图中各节点的子树大小及父节点关系。
     * 根节点的 parentArray[localRoot] 将被设为 -1。
     */
    private static void computeSubtreeSizes(
            int localRoot,
            List<List<Integer>> localAdj,
            int[] subtreeSize,
            int[] parentArray) {

        Arrays.fill(parentArray, -1);

        boolean[] seen = new boolean[localAdj.size()];
        List<Integer> visitOrder = new ArrayList<>(localAdj.size());
        Deque<Integer> stack = new ArrayDeque<>();

        stack.push(localRoot);
        seen[localRoot] = true;

        while (!stack.isEmpty()) {
            int cur = stack.pop();
            visitOrder.add(cur);
            for (int nb : localAdj.get(cur)) {
                if (!seen[nb]) {
                    stack.push(nb);
                    seen[nb] = true;
                    parentArray[nb] = cur;
                }
            }
        }

        for (int i = visitOrder.size() - 1; i >= 0; i--) {
            int cur = visitOrder.get(i);
            int sz = 1;
            for (int nb : localAdj.get(cur)) {
                if (nb != parentArray[cur]) {
                    sz += subtreeSize[nb];
                }
            }
            subtreeSize[cur] = sz;
        }
    }

    /**
     * 从起始点向下遍历，收集连通块内的所有 Local ID。
     * 利用 blockedParentLocalId 阻断向上回溯的路径，实现子树切割。
     */
    private static List<Integer> collectSubtreeNodes(
            int startLocalId,
            int blockedParentLocalId,
            List<List<Integer>> localAdj) {

        List<Integer> result = new ArrayList<>();
        boolean[] seen = new boolean[localAdj.size()];
        Deque<Integer> stack = new ArrayDeque<>();

        stack.push(startLocalId);
        seen[startLocalId] = true;

        while (!stack.isEmpty()) {
            int cur = stack.pop();
            result.add(cur);

            for (int nb : localAdj.get(cur)) {
                if (nb != blockedParentLocalId && !seen[nb]) {
                    stack.push(nb);
                    seen[nb] = true;
                }
            }
        }
        return result;
    }
}

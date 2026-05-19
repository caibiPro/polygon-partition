package com.mingqing.study;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class TreePartition {

    public static int dfsSubtreeSize(
            int node,
            List<List<Integer>> tree,
            int[] subtreeSize,
            int[] parentArray) {

        if (tree == null) {
            throw new IllegalArgumentException("tree is null");
        }
        if (subtreeSize == null) {
            throw new IllegalArgumentException("subtreeSize is null");
        }
        if (node >= tree.size() || node < 0) {
            throw new IllegalArgumentException("node " + node + " is out of tree");
        }
        if (subtreeSize.length != tree.size()) {
            throw new IllegalArgumentException("subtreeSize length " + subtreeSize.length + " != tree.size");
        }
        if (parentArray.length != subtreeSize.length) {
            throw new IllegalArgumentException("parentArray.length " + parentArray.length + " != subtreeSize.length");
        }

        Deque<Integer> stack = new ArrayDeque<>();
        List<Integer> visitOrder = new ArrayList<>();
        boolean[] seen = new boolean[tree.size()];
        stack.push(node);
        seen[node] = true;

        while (!stack.isEmpty()) {
            int cur = stack.pop();
            visitOrder.add(cur);
            for (Integer neighbour : tree.get(cur)) {
                if (!seen[neighbour]) {
                    seen[neighbour] = true;
                    parentArray[neighbour] = cur;
                    stack.push(neighbour);
                }
            }
        }

        for (int i = visitOrder.size() - 1; i >= 0; i--) {
            int cur = visitOrder.get(i);
            int subSize = 1;
            for (Integer neighbour : tree.get(cur)) {
                if (neighbour != parentArray[cur]) {
                    subSize += subtreeSize[neighbour];
                }
            }
            subtreeSize[cur] = subSize;
        }

        return subtreeSize[node];
    }
}

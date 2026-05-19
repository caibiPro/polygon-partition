package com.mingqing.study;

import java.util.*;

public class CutEdge {
    private final int parent;
    private final int child;
    private final int leftSize;
    private final int rightSize;
    private final int imbalance;

    public CutEdge(int parent, int child, int leftSize, int rightSize) {
        this.parent = parent;
        this.child = child;
        this.leftSize = leftSize;
        this.rightSize = rightSize;
        this.imbalance = Math.abs(leftSize - rightSize);
    }

    public int getParent() {
        return parent;
    }

    public int getChild() {
        return child;
    }

    public int getLeftSize() {
        return leftSize;
    }

    public int getRightSize() {
        return rightSize;
    }

    public int getImbalance() {
        return imbalance;
    }

    public static CutEdge findBestBalancedCut(int[] parentArray, int[] subtreeSize) {
        if (parentArray == null || subtreeSize == null) {
            throw new IllegalArgumentException("parentArray and subtreeSize can't be null");
        }
        if (parentArray.length != subtreeSize.length) {
            throw new IllegalArgumentException("parentArray.length != subtreeSize.length");
        }

        CutEdge best = null;
        int count = parentArray.length;

        for (int i = 0; i < parentArray.length; i++) {
            int parent = parentArray[i];
            if (parent < 0) {
                continue;
            }

            int subSize = subtreeSize[i];
            int anotherSubSize = count - subSize;
            int imbalance = Math.abs(subSize - anotherSubSize);

            if (best == null || best.imbalance > imbalance) {
                best = new CutEdge(parent, i, subSize, anotherSubSize);
            }
        }

        return best;
    }

    public static List<Integer> collectSubtreeAfterCut(
            int start,
            int blockedParent,
            List<List<Integer>> tree) {
        if (tree == null || tree.isEmpty()) {
            throw new IllegalArgumentException("tree can't be null or empty");
        }
        if (start == blockedParent) {
            throw new IllegalArgumentException("blockedParent can't be start node");
        }
        if (start >= tree.size() || start < 0) {
            throw new IllegalArgumentException("start node out of range");
        }
        if (blockedParent >= tree.size() || blockedParent < -1) {
            throw new IllegalArgumentException("blockedParent out of range");
        }

        List<Integer> result = new ArrayList<>();
        int count = tree.size();
        Deque<Integer> stack = new ArrayDeque<>(count);
        boolean[] visited = new boolean[count];
        stack.push(start);

        while (!stack.isEmpty()) {
            int pop = stack.pop();
            if (visited[pop]) {
                continue;
            }

            visited[pop] = true;
            result.add(pop);
            List<Integer> children = tree.get(pop);

            for (Integer child : children) {
                if (child != blockedParent) {
                    stack.push(child);
                }
            }
        }

        return result;
    }
}

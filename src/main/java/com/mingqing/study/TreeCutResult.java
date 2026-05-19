package com.mingqing.study;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TreeCutResult {
    private final CutEdge cutEdge;
    private final List<Integer> partA;
    private final List<Integer> partB;

    public TreeCutResult(CutEdge cutEdge, List<Integer> partA, List<Integer> partB) {
        this.cutEdge = cutEdge;
        this.partA = new ArrayList<>(partA);
        this.partB = new ArrayList<>(partB);
    }

    public List<Integer> getPartA() {
        return partA;
    }

    public List<Integer> getPartB() {
        return partB;
    }

    public static TreeCutResult cutOnce(List<List<Integer>> tree) {
        if (tree == null) {
            throw new IllegalArgumentException("Tree is null");
        }
        int size = tree.size();
        if (size < 2) {
            return null;
        }

        int[] subtreeSize = new int[size];
        int[] parentArray = new int[size];
        Arrays.fill(parentArray, -1);

        TreePartition.dfsSubtreeSize(0, tree, subtreeSize, parentArray);
        CutEdge cut = CutEdge.findBestBalancedCut(parentArray, subtreeSize);
        if (cut == null) {
            return null;
        }

        List<Integer> partA = CutEdge.collectSubtreeAfterCut(cut.getChild(), cut.getParent(), tree);
        List<Integer> partB = CutEdge.collectSubtreeAfterCut(cut.getParent(), cut.getChild(), tree);

        return new TreeCutResult(cut, partA, partB);
    }
}

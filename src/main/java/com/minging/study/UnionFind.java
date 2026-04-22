package com.minging.study;

public class UnionFind {

    private final int[] parent;

    public UnionFind(int n) {
        parent = new int[n];

        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
    }

    public int find(int x) {
        validateElement(x);

        while (x != parent[x]) {
            x = parent[x];
        }

        return x;
    }

    public void union(int a, int b) {
        validateElement(a);
        validateElement(b);

        int rootA = find(a);
        int rootB = find(b);


        if (rootA != rootB) {
            parent[rootA] = rootB;
        }
    }

    public boolean connected(int a, int b) {
        validateElement(a);
        validateElement(b);

        return find(a) == find(b);
    }

    private void validateElement(int x) {
        if (x < 0 || x >= parent.length) {
            throw new IllegalArgumentException("invalid element: " + x);
        }
    }
}

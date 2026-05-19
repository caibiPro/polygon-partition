package com.mingqing.partition.graph;

public class UnionFind {

    private final int[] parent;
    private final int[] rank;

    public UnionFind(int n) {
        parent = new int[n];
        rank = new int[n];

        for (int i = 0; i < n; i++) {
            parent[i] = i;
            rank[i] = 0;
        }
    }

    public int find(int x) {
        validateElement(x);

        if (parent[x] == x) {
            return x;
        }

        parent[x] = find(parent[x]);

        return parent[x];
    }

    public void union(int a, int b) {
        validateElement(a);
        validateElement(b);

        int rootA = find(a);
        int rootB = find(b);

        if (rootA == rootB) {
            return;
        }

        int rankA = rank[rootA];
        int rankB = rank[rootB];

        if (rankA < rankB) {
            parent[rootA] = rootB;
        } else if (rankA > rankB) {
            parent[rootB] = rootA;
        } else {
            parent[rootA] = rootB;
            rank[rootB]++;
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

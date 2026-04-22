package com.minging.study;

import java.util.ArrayList;
import java.util.List;

public class Kruskal {

    public static List<Edge> maxSpanningTree(int n, List<Edge> edges) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        if (edges == null) {
            throw new IllegalArgumentException("edges must not be null");
        }
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("edges must not be empty when n > 1");
        }
        if (n == 1) {
            return new ArrayList<>();
        }

        List<Edge> sortedEdges = edges.stream().sorted().toList();
        UnionFind uf = new UnionFind(n);
        List<Edge> result = new ArrayList<>();

        for (Edge edge : sortedEdges) {
            int u = edge.getU();
            int v = edge.getV();

            if (!uf.connected(u, v))  {
                uf.union(u, v);
                result.add(edge);

                if (result.size() == n - 1)  {
                    break;
                }
            }
        }

        if (result.size() != n - 1) {
            throw new IllegalStateException("graph is disconnected, so a spanning tree cannot be formed");
        }

        return result;
    }
}

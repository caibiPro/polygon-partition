package com.minging.study;

public class Edge implements Comparable<Edge> {
    private final int u;
    private final int v;
    private final int weight;

    public Edge(int u, int v, int weight) {
        this.u = u;
        this.v = v;
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    @Override
    public int compareTo(Edge o) {
        return Integer.compare(o.weight, this.weight);
    }

    public int getU() {
        return u;
    }

    public int getV() {
        return v;
    }
}

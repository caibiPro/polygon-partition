package com.mingqing.study;

import java.util.*;

public class Graph {

    private final List<List<Integer>> adjList;

    public Graph(int n) {
        adjList = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjList.add(new ArrayList<>());
        }
    }

    private void validateVertex(int v) {
        if (v < 0 || v >= adjList.size()) {
            throw new IllegalArgumentException("vertex " + v + " is not between 0 and " + (adjList.size() - 1));
        }
    }

    public void addUndirectedEdge(int u, int v) {
        // 顶点有效性检查
        validateVertex(u);
        validateVertex(v);

        // 自环检查
        if (u == v) {
            throw new IllegalArgumentException("vertex " + u + " is the same vertex");
        }

        // 重复边检查
        if (adjList.get(u).contains(v)) {
            throw new IllegalArgumentException("edge " + u + " and " + v + " already exist");
        }

        adjList.get(u).add(v);
        adjList.get(v).add(u);
    }

    public List<Integer> neighbors(int u) {
        validateVertex(u);
        return Collections.unmodifiableList(adjList.get(u));
    }

    public int size() {
        return adjList.size();
    }

    public static List<Integer> dfsComponent(Graph graph, int start, boolean[] visited) {
        List<Integer> components = new ArrayList<>();
        if (graph == null || start < 0 || start >= graph.adjList.size()) {
            return components;
        }

        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);

        while (!stack.isEmpty()) {
            int v = stack.pop();
            if (visited[v]) {
                continue;
            }
            visited[v] = true;
            components.add(v);

            List<Integer> neighbors = graph.neighbors(v);
            for (Integer neighbor : neighbors) {
                stack.push(neighbor);
            }
        }

        return components;
    }

    public static List<List<Integer>> connectedComponents(Graph graph) {
        List<List<Integer>> components = new ArrayList<>();
        if (graph == null) {
            return components;
        }

        boolean[] visited = new boolean[graph.size()];
        for (int i = 0; i < graph.size(); i++) {
            if (!visited[i]) {
                components.add(dfsComponent(graph, i, visited));
            }
        }
        return components;
    }

    public static boolean isConnectedSubset(Graph graph, Set<Integer> subset) {
        if (graph == null) {
            throw new IllegalArgumentException("graph is null");
        }
        if (subset == null) {
            throw new IllegalArgumentException("subset is null");
        }
        if (subset.isEmpty()) {
            return true;
        }
        for (Integer v : subset) {
            if (v < 0 || v >= graph.size()) {
                throw new IllegalArgumentException("vertex " + v + " is not between 0 and " + (graph.size() - 1));
            }
        }

        boolean[] visited = new boolean[graph.size()];
        int components = 0;
        Deque<Integer> stack = new ArrayDeque<>();

        Integer first = subset.iterator().next();
        stack.push(first);

        while (!stack.isEmpty()) {
            int v = stack.pop();
            if (visited[v]) {
                continue;
            }

            visited[v] = true;
            components++;

            for (Integer neighbor : graph.neighbors(v)) {
                if (subset.contains(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }

        return components == subset.size();
    }


    public static void main(String[] args) {
        Graph graph = new Graph(6);
        graph.addUndirectedEdge(0, 1);
        graph.addUndirectedEdge(0, 2);
        graph.addUndirectedEdge(1, 2);
        graph.addUndirectedEdge(3, 4);

        List<List<Integer>> integers = connectedComponents(graph);
        for (int i = 0; i < integers.size(); i++) {
            List<Integer> component = integers.get(i);
            System.out.println("component[" + i + "]: " + component);
        }
    }
}

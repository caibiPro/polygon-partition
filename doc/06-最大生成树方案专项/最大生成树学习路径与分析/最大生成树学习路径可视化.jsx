import { useState, useCallback, useMemo } from "react";

// ============================================================
// 学习路径数据
// ============================================================

const STAGES = [
  {
    id: 0,
    title: "你已经知道的",
    subtitle: "数据结构课的图知识",
    icon: "✅",
    color: "#10B981",
    knowledge: [
      "图 = 节点(V) + 边(E)",
      "邻接矩阵 / 邻接表",
      "BFS 广度优先遍历",
      "DFS 深度优先遍历",
      "可能接触过最短路径",
    ],
    gap: "这些足够理解70%的内容了。缺的不多，但每个都很关键。",
  },
  {
    id: 1,
    title: "第一关：并查集",
    subtitle: "Union-Find — 20分钟掌握",
    icon: "🧩",
    color: "#3B82F6",
    knowledge: [
      "回答一个问题：两个节点是否连通？",
      "数组实现：parent[i] = i的父节点",
      "find(x)：顺着parent往上找到根",
      "union(a,b)：把一个根挂到另一个根下面",
      "路径压缩：find时把沿途节点直接挂到根上",
      "→ 之后每次find近乎O(1)",
    ],
    why: "Kruskal算法的核心。每加一条边，要判断两端是否已连通（已连通→会成环→不选）。没有并查集，这个判断是O(N)的BFS；有了并查集，是O(1)的。",
    exercise: "exercise_union_find",
  },
  {
    id: 2,
    title: "第二关：生成树",
    subtitle: "Spanning Tree — 理解树的本质",
    icon: "🌲",
    color: "#F59E0B",
    knowledge: [
      "树 = 无环连通图",
      "N个节点的树恰好有N-1条边",
      "★ 删除任意一条边 → 恰好裂成两个连通分量",
      "★ 任意两个节点之间恰好有一条路径",
      "生成树 = 从原图中选N-1条边，保持连通、无环",
      "一个图可能有很多不同的生成树",
    ],
    why: "树的'删一条边裂成两半'性质，是整个方案的数学基础。图没有这个性质——删一条边可能图还是连通的。",
    exercise: "exercise_spanning_tree",
  },
  {
    id: 3,
    title: "第三关：Kruskal算法",
    subtitle: "贪心构建最小/最大生成树",
    icon: "⚡",
    color: "#8B5CF6",
    knowledge: [
      "Kruskal = 贪心 + 并查集",
      "最小ST：边按权重从小到大排序",
      "最大ST：边按权重从大到小排序（我们需要的）",
      "依次取边，若两端不连通→选入，否则跳过",
      "选够N-1条边后停止",
    ],
    why: "只改排序方向就能得到最大生成树。这棵树保留了'最强的连接'，切割时自然从'最弱处'切开。",
    exercise: "exercise_kruskal",
  },
  {
    id: 4,
    title: "第四关：子树大小计算",
    subtitle: "DFS后序遍历 — 切树的前置技能",
    icon: "📏",
    color: "#EC4899",
    knowledge: [
      "有根树：选一个根，其他节点都有唯一父节点",
      "子树大小 = 1(自己) + 所有子节点的子树大小",
      "后序遍历：先算完所有子节点，再算自己",
      "叶子节点的子树大小 = 1",
      "根节点的子树大小 = N（全部节点）",
    ],
    why: "当我们要删除边(u,v)时，需要知道两侧各有多少节点。v的子树大小就是一侧，N-subtreeSize[v]就是另一侧。",
    exercise: "exercise_subtree_size",
  },
  {
    id: 5,
    title: "第五关：递归平衡切树",
    subtitle: "把所有技能组合起来",
    icon: "✂️",
    color: "#EF4444",
    knowledge: [
      "遍历每条树边(u,v)，计算删除后两侧大小",
      "选两侧差最小的（最均衡）",
      "若均衡度相同，选权重最小的边",
      "切断后得到两个子树",
      "对超过阈值的子树递归处理",
    ],
    why: "所有前面的知识在这里汇合：并查集建树，子树大小选切割边，BFS分离分量。",
    exercise: "exercise_balanced_cut",
  },
  {
    id: 6,
    title: "第六关：接入GIS",
    subtitle: "从纯图论到空间问题",
    icon: "🗺️",
    color: "#06B6D4",
    knowledge: [
      "JTS的Geometry体系",
      "STR-tree空间索引",
      "intersection判断共边",
      "共边长度 → 边权",
      "CascadedPolygonUnion恢复几何",
    ],
    why: "图论解决了'怎么分组'的问题，JTS解决了'图斑怎么变成图的节点和边'以及'分组结果怎么变回多边形'的问题。",
    exercise: "exercise_gis",
  },
];

// ============================================================
// 每一关的代码练习
// ============================================================

const EXERCISES = {
  exercise_union_find: {
    title: "并查集：从零实现",
    steps: [
      {
        title: "最简版本（能用但慢）",
        code: `// 并查集：回答"两个节点是否连通"
class UnionFind {
    int[] parent;  // parent[i] = i的父节点
    
    UnionFind(int n) {
        parent = new int[n];
        for (int i = 0; i < n; i++)
            parent[i] = i;  // 初始：每个人是自己的老大
    }
    
    // 找老大（根节点）
    int find(int x) {
        while (parent[x] != x)  // 不断往上找
            x = parent[x];
        return x;
    }
    
    // 合并两个集合
    void union(int a, int b) {
        parent[find(a)] = find(b);  // a的老大认b的老大做老大
    }
    
    // 判断是否连通
    boolean connected(int a, int b) {
        return find(a) == find(b);
    }
}

// 试试看：
UnionFind uf = new UnionFind(5);  // 0,1,2,3,4
uf.union(0, 1);  // 0和1连通
uf.union(2, 3);  // 2和3连通
uf.connected(0, 1);  // true
uf.connected(0, 2);  // false
uf.union(1, 2);       // 现在0-1-2-3全连通
uf.connected(0, 3);  // true!`,
        explanation: "这个版本能工作，但find()最坏O(N)——想象一条链 0→1→2→3→...→N。",
      },
      {
        title: "加路径压缩（关键优化）",
        code: `// 路径压缩版 find
int find(int x) {
    if (parent[x] != x)
        parent[x] = find(parent[x]);  // ← 关键！递归回来时顺手把路径上的节点都直接挂到根上
    return parent[x];
}

// 效果：
//   压缩前: 0 → 1 → 2 → 3 (根)
//   find(0)后: 0 → 3
//              1 → 3
//              2 → 3
//   之后find(0)就是O(1)了

// 加上按秩合并（小树挂大树下面，保持矮胖）
class UnionFind {
    int[] parent, rank;
    
    UnionFind(int n) {
        parent = new int[n];
        rank = new int[n];  // rank≈树的高度
        for (int i = 0; i < n; i++) parent[i] = i;
    }
    
    int find(int x) {
        if (parent[x] != x) parent[x] = find(parent[x]);
        return parent[x];
    }
    
    boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;   // 已经连通，不用合并
        if (rank[ra] < rank[rb]) { int t=ra; ra=rb; rb=t; }
        parent[rb] = ra;              // 矮树挂高树下面
        if (rank[ra] == rank[rb]) rank[ra]++;
        return true;  // 返回true表示确实合并了（很有用！）
    }
}`,
        explanation: "路径压缩+按秩合并后，单次操作均摊O(α(N))，α是反阿克曼函数，对任何实际N都≤4。就当O(1)用。",
      },
      {
        title: "✏️ 动手练习",
        code: `// 练习：手动模拟以下操作后parent数组的状态
// 初始: parent = [0, 1, 2, 3, 4, 5]
//
// union(1, 2)  → parent = ?
// union(3, 4)  → parent = ?
// union(1, 4)  → parent = ?  (想想1的根和4的根是谁)
// find(3)      → 返回?  (路径压缩后parent变了吗?)
// connected(2, 3) → ?

// 答案在你写完后对照：
// union(1,2): parent = [0, 2, 2, 3, 4, 5]  (1挂到2下面)
// union(3,4): parent = [0, 2, 2, 4, 4, 5]  (3挂到4下面)
// union(1,4): find(1)=2, find(4)=4 → parent = [0, 2, 4, 4, 4, 5] (2挂到4)
// find(3): parent[3]=4, parent[4]=4 → 返回4 (已经直接指向根,无压缩)
// connected(2,3): find(2)=4, find(3)=4 → true!`,
        explanation: "手动模拟是理解并查集最好的方式。画出树形结构图会更直观。",
      },
    ],
  },
  
  exercise_spanning_tree: {
    title: "生成树：理解'删边裂两半'",
    steps: [
      {
        title: "树的核心性质",
        code: `// 一个6节点的树：
//
//     0
//    / \\
//   1   2
//  / \\   \\
// 3   4   5
//
// 邻接表:
int[][] tree = {{1,2}, {0,3,4}, {0,5}, {1}, {1}, {2}};

// 性质1: 5条边 = 6个节点 - 1 ✓
// 性质2: 任意两点间恰好一条路径
//   0到5: 0→2→5 (唯一)
//   3到5: 3→1→0→2→5 (唯一)

// ★ 性质3: 删除任意一条边 → 恰好裂成两个连通分量
// 删除边(0,2):
//   分量A: {0,1,3,4}  (通过0-1相连)
//   分量B: {2,5}       (通过2-5相连)
//   大小: 4 和 2
// 
// 删除边(1,4):
//   分量A: {0,1,2,3,5}
//   分量B: {4}  (4变成孤立的)
//   大小: 5 和 1

// 为什么图没有这个性质？
// 图可能有环：
//   0 - 1
//   |   |
//   2 - 3
// 删除边(0,1): 0还能通过0→2→3→1到达1
// 图依然连通！没有裂开。`,
        explanation: "树=无环，所以两个节点之间只有一条路径。删掉路径上任意一条边，连接就断了，必然裂成两部分。",
      },
      {
        title: "为什么这个性质对我们如此重要",
        code: `// 我们的问题：把N个图斑分成若干组，每组≤K个
// 
// 如果用"图"来做：
//   删除一条边 → 图可能还是连通的 → 没有分开
//   要分开，可能需要删很多条边
//   而且很难控制两侧大小
//
// 如果用"树"来做：
//   删除一条边 → 保证裂成两组
//   而且可以精确知道两侧各有多少个节点
//   选最均衡的那条边删除
//   完美！
//
// 这就是为什么我们要从邻接图中提取一棵生成树
// 生成树 = 保留了图的连通性，但去掉了"多余"的边
// 使得切割变得确定、可控`,
        explanation: "树结构让'分割'变成了一个确定性操作：删一条边=分两组，没有歧义。",
      },
    ],
  },

  exercise_kruskal: {
    title: "Kruskal：贪心建最大生成树",
    steps: [
      {
        title: "算法流程",
        code: `// 输入：图的所有边 (u, v, weight)
// 输出：最大生成树的边集合

List<int[]> kruskalMaxST(int n, List<int[]> edges) {
    // Step 1: 按权重从大到小排序
    edges.sort((a, b) -> b[2] - a[2]);  // 降序！
    
    // Step 2: 初始化并查集
    UnionFind uf = new UnionFind(n);
    
    // Step 3: 贪心选边
    List<int[]> tree = new ArrayList<>();
    for (int[] e : edges) {
        int u = e[0], v = e[1], w = e[2];
        if (uf.union(u, v)) {  // 不连通 → 选入
            tree.add(e);
            if (tree.size() == n - 1) break;  // 够了
        }
        // 已连通 → 跳过（选了会成环）
    }
    return tree;  // N-1条边
}`,
        explanation: "Kruskal的精妙之处：并查集的union返回false=已连通=会成环，返回true=不连通=安全选入。",
      },
      {
        title: "✏️ 手动模拟",
        code: `// 练习：手动跑Kruskal最大生成树
//
// 图：     A ——8—— B ——3—— C
//          |       |       |
//          4       9       6
//          |       |       |
//          D ——7—— E ——5—— F
//
// Step 1: 排序(降序)
//   B-E(9), A-B(8), D-E(7), C-F(6), E-F(5), A-D(4), B-C(3)
//
// Step 2: 逐条尝试
//   B-E(9): B和E不连通 → ✅ 选入     tree=[B-E]
//   A-B(8): A和B不连通 → ✅ 选入     tree=[B-E, A-B]
//   D-E(7): D和E不连通 → ✅ 选入     tree=[B-E, A-B, D-E]
//   C-F(6): C和F不连通 → ✅ 选入     tree=[B-E, A-B, D-E, C-F]
//   E-F(5): E和F? find(E)=?, find(F)=?
//           E→B(通过B-E), F→C(通过C-F) → 不连通 → ✅ 选入
//           tree=[B-E, A-B, D-E, C-F, E-F]
//           5条边 = 6节点-1 → 完成！
//
// 被丢弃: A-D(4), B-C(3) — 这两条是最弱的连接
//
// 最大生成树:
//     A ——8—— B     C
//             |     |
//             9     6
//             |     |
//     D ——7—— E ——5—— F
//
// 如果要切一刀，最弱的树边是 E-F(5)
// 切断后: {A,B,D,E}(4个) 和 {C,F}(2个)`,
        explanation: "注意被丢弃的边(A-D, B-C)恰好是权重最小的。最大生成树留下了最强的连接骨架。",
      },
    ],
  },

  exercise_subtree_size: {
    title: "子树大小：DFS后序遍历",
    steps: [
      {
        title: "后序遍历计算子树大小",
        code: `// 树:      0 (根)
//         / \\
//        1   2
//       /|    \\
//      3  4    5
//     /
//    6
//
// 从叶子往上算:
//   subtreeSize[6] = 1
//   subtreeSize[3] = 1 + subtreeSize[6] = 2
//   subtreeSize[4] = 1
//   subtreeSize[1] = 1 + subtreeSize[3] + subtreeSize[4] = 4
//   subtreeSize[5] = 1
//   subtreeSize[2] = 1 + subtreeSize[5] = 2
//   subtreeSize[0] = 1 + subtreeSize[1] + subtreeSize[2] = 7 (=N)

int[] computeSubtreeSize(List<List<Integer>> adj, int root) {
    int n = adj.size();
    int[] size = new int[n];
    Arrays.fill(size, 1);  // 每个节点至少包含自己
    int[] parent = new int[n];
    Arrays.fill(parent, -1);
    
    // BFS得到遍历顺序
    List<Integer> order = new ArrayList<>();
    boolean[] visited = new boolean[n];
    Queue<Integer> queue = new LinkedList<>();
    queue.add(root);
    visited[root] = true;
    while (!queue.isEmpty()) {
        int u = queue.poll();
        order.add(u);
        for (int v : adj.get(u)) {
            if (!visited[v]) {
                visited[v] = true;
                parent[v] = u;
                queue.add(v);
            }
        }
    }
    
    // 逆序累加（后序：先处理子节点，再处理父节点）
    for (int i = order.size() - 1; i >= 0; i--) {
        int u = order.get(i);
        if (parent[u] >= 0) {
            size[parent[u]] += size[u];
        }
    }
    
    return size;
}`,
        explanation: "为什么用BFS+逆序而不是递归DFS？因为10万节点时递归可能栈溢出。BFS+逆序是迭代版后序遍历的标准技巧。",
      },
      {
        title: "子树大小 → 切割决策",
        code: `// 有了子树大小，切割变得简单：
//
// 树(以0为根):    0
//                / \\
//               1   2
//              /|    \\
//             3  4    5
//            /
//           6
//
// subtreeSize = [7, 4, 2, 2, 1, 1, 1]
//
// 删除边(0,1):
//   侧A: subtreeSize[1] = 4 (节点1子树)
//   侧B: 7 - 4 = 3          (其余)
//   差: |4-3| = 1            ← 很均衡！
//
// 删除边(1,3):
//   侧A: subtreeSize[3] = 2
//   侧B: 7 - 2 = 5
//   差: |2-5| = 3            ← 不太均衡
//
// 删除边(0,2):
//   侧A: subtreeSize[2] = 2
//   侧B: 7 - 2 = 5
//   差: |2-5| = 3
//
// 最均衡的切割: 删边(0,1)，两侧4和3

// ✏️ 练习：算出删除每条边后的两侧大小
// 然后找到最均衡的切割边`,
        explanation: "子树大小就是'秤'——让你精确知道从哪里切开能最均衡。",
      },
    ],
  },

  exercise_balanced_cut: {
    title: "组合：递归平衡切树",
    steps: [
      {
        title: "完整流程串联",
        code: `// 把前面所有技能组合起来！

List<Set<Integer>> splitTree(Tree tree, int threshold) {
    List<Set<Integer>> results = new ArrayList<>();
    
    // 所有边初始为活跃
    boolean[] active = new boolean[tree.edges.size()];
    Arrays.fill(active, true);
    
    // 初始：所有节点在一组
    Set<Integer> all = new HashSet<>();
    for (int i = 0; i < tree.n; i++) all.add(i);
    
    recursiveCut(tree, all, active, threshold, results);
    return results;
}

void recursiveCut(Tree tree, Set<Integer> nodes, 
                  boolean[] active, int threshold,
                  List<Set<Integer>> results) {
    // 终止条件
    if (nodes.size() <= threshold) {
        results.add(nodes);
        return;
    }
    
    // 1. 在当前子树上计算子树大小（用前面学的）
    int root = nodes.iterator().next();
    int[] subSize = computeLocalSubtreeSize(tree, nodes, active, root);
    int total = nodes.size();
    
    // 2. 找最均衡的切割边
    int bestEdge = -1;
    int bestDiff = Integer.MAX_VALUE;
    double bestWeight = Double.MAX_VALUE;
    
    for (int i = 0; i < tree.edges.size(); i++) {
        if (!active[i]) continue;
        Edge e = tree.edges.get(i);
        if (!nodes.contains(e.u) || !nodes.contains(e.v)) continue;
        
        int childSize = subSize[e.v]; // 假设v是u的子节点
        int otherSize = total - childSize;
        int diff = Math.abs(childSize - otherSize);
        
        if (diff < bestDiff || 
            (diff == bestDiff && e.weight < bestWeight)) {
            bestDiff = diff;
            bestWeight = e.weight;
            bestEdge = i;
        }
    }
    
    // 3. 切！
    active[bestEdge] = false;
    
    // 4. BFS分离两个连通分量
    Set<Integer> compA = bfs(tree, tree.edges.get(bestEdge).u, 
                             nodes, active);
    Set<Integer> compB = new HashSet<>(nodes);
    compB.removeAll(compA);
    
    // 5. 递归
    recursiveCut(tree, compA, active, threshold, results);
    recursiveCut(tree, compB, active, threshold, results);
}`,
        explanation: "就这么多代码。核心逻辑清晰：计算大小→选最均衡边→切→递归。没有任何几何操作。",
      },
      {
        title: "✏️ 完整手动模拟",
        code: `// 练习：对下面这棵最大生成树做递归切割，阈值K=3
//
//     A ——8—— B
//             |
//             9
//             |
//     D ——7—— E ——5—— F
//             |       |
//             8       6
//             |       |
//             H       C
//
// 8个节点，阈值3，需要 ⌈8/3⌉ = 3 个区域
//
// 第一步：以A为根，算子树大小
//   subtreeSize[A] = ?
//   subtreeSize[B] = ?
//   subtreeSize[E] = ?
//   ...
//
// 第二步：遍历7条树边，找最均衡的
//   删A-B: [A](?) vs [其余](?)  差=?
//   删B-E: [A,B](?) vs [其余](?) 差=?
//   删D-E: ...
//   删E-F: ...
//   删E-H: ...
//   删F-C: ...
//
// 第三步：切最均衡的那条
// 第四步：对超过阈值的子树继续切
//
// 你的最终答案是哪3个区域？每个区域几个节点？`,
        explanation: "强烈建议在纸上画出来。画图是理解图论最好的方式。",
      },
    ],
  },

  exercise_gis: {
    title: "接入GIS：图斑 → 图 → 结果",
    steps: [
      {
        title: "从图斑到邻接图",
        code: `// 前面都是纯图论。现在接入真实的GIS数据。

// 核心问题：怎么知道两个图斑"相邻"？
// 答：它们的边界有公共线段（共边）

// JTS代码：
Geometry parcelA = ...; // 图斑A的多边形
Geometry parcelB = ...; // 图斑B的多边形

// 计算两个多边形边界的交集
Geometry intersection = parcelA.intersection(parcelB);

// 交集的维度告诉我们关系：
// dimension = -1  → 不相交
// dimension = 0   → 共点（只在角上碰了一下）
// dimension = 1   → 共边（有公共线段）← 我们要的！
// dimension = 2   → 重叠（有公共面积，说明数据有问题）

if (intersection.getDimension() >= 1) {
    double sharedLength = intersection.getLength();
    // sharedLength 就是共边长度 → 边权！
    addEdge(indexA, indexB, sharedLength);
}`,
        explanation: "JTS的intersection()是核心操作。dimension()判断交集类型是非常实用的技巧。",
      },
      {
        title: "STR-tree加速邻居查找",
        code: `// 10万图斑两两比较 = 50亿次 → 不可行
// STR-tree把复杂度降到O(N log N)

// Step 1: 所有图斑包围盒(AABB)插入空间索引
STRtree index = new STRtree();
for (int i = 0; i < parcels.size(); i++) {
    index.insert(parcels.get(i).getEnvelopeInternal(), i);
}
index.build();  // STR-tree是批量构建的

// Step 2: 对每个图斑，查询"可能相邻"的候选者
for (int i = 0; i < parcels.size(); i++) {
    Envelope queryBox = parcels.get(i).getEnvelopeInternal();
    queryBox.expandBy(0.001);  // 微扩一点，处理精度问题
    
    List<Integer> candidates = index.query(queryBox);
    // candidates通常只有8~12个，而不是10万个！
    
    for (int j : candidates) {
        if (j <= i) continue;  // 避免重复
        // 只对候选者做精确的intersection检查
        // ...
    }
}

// 为什么叫STR-tree？
// Sort-Tile-Recursive:
//   Sort: 按X排序
//   Tile: 分成√N组
//   Recursive: 每组内按Y排序，递归构建
// 结果：包围盒重叠很少，查询极快`,
        explanation: "STR-tree是'先全部插入，后批量查询'的最佳数据结构。我们的场景完美匹配。",
      },
      {
        title: "从分组结果恢复几何边界",
        code: `// 图论给出了分组结果: 节点0,1,2在组A; 节点3,4,5在组B
// 现在需要给每个组一个"区域边界"多边形

// 最大生成树方案的优势在这里体现：
// 因为每组的图斑保证连通，所以Union结果是一个连续的区域

List<Geometry> groupParcels = ...; // 某组的所有图斑

// Union合并
Geometry merged = CascadedPolygonUnion.union(groupParcels);
// CascadedPolygonUnion比逐个union快很多（级联合并策略）

// 可能需要小缓冲填补道路间隙
Geometry buffered = merged.buffer(0.5).buffer(-0.3);
// 先膨胀0.5米填补间隙，再收缩0.3米避免过度膨胀

// 最后与村边界求交，确保不超出
Geometry regionBoundary = village.intersection(buffered);`,
        explanation: "连通性保证让几何恢复变得简单——不会出现一个组的图斑分散在几个不连通的区域。",
      },
    ],
  },
};

// ============================================================
// 推荐学习材料
// ============================================================

const RESOURCES = [
  { category: "并查集", items: [
    { name: "《算法4》第1.5节 Union-Find", why: "最清晰的并查集讲解，配有大量图示", type: "book" },
    { name: "LeetCode 547: 省份数量", why: "并查集入门练手题", type: "practice" },
    { name: "LeetCode 684: 冗余连接", why: "并查集判环，对理解Kruskal有帮助", type: "practice" },
  ]},
  { category: "生成树 + Kruskal", items: [
    { name: "《算法4》第4.3节 最小生成树", why: "Kruskal和Prim的对比讲解", type: "book" },
    { name: "LeetCode 1584: 连接所有点的最小费用", why: "Kruskal实战", type: "practice" },
    { name: "Visualgo.net/mst", why: "在线可视化Kruskal每一步", type: "tool" },
  ]},
  { category: "树上算法", items: [
    { name: "LeetCode 543: 二叉树的直径", why: "练习子树大小/DFS后序", type: "practice" },
    { name: "LeetCode 310: 最小高度树", why: "理解树的中心性质", type: "practice" },
  ]},
  { category: "JTS / GIS", items: [
    { name: "JTS TestBuilder", why: "JTS自带的GUI工具，可视化几何操作", type: "tool" },
    { name: "JTS GitHub上的单元测试", why: "学JTS最好的材料是读它的测试", type: "code" },
    { name: "PostGIS ST_Subdivide文档", why: "类似功能的参考实现思路", type: "doc" },
  ]},
];

// ============================================================
// UI 组件
// ============================================================

const BG = '#0F172A';
const CARD_BG = '#1E293B';
const TEXT = '#E2E8F0';
const DIM = '#94A3B8';
const ACCENT = '#38BDF8';

export default function LearningPath() {
  const [selectedStage, setSelectedStage] = useState(null);
  const [exerciseStep, setExerciseStep] = useState(0);
  const [showResources, setShowResources] = useState(false);

  const currentExercise = selectedStage !== null && STAGES[selectedStage]?.exercise
    ? EXERCISES[STAGES[selectedStage].exercise]
    : null;

  return (
    <div style={{
      background: BG, color: TEXT, minHeight: '100vh',
      fontFamily: "'JetBrains Mono', 'SF Mono', monospace",
      padding: 24, maxWidth: 900, margin: '0 auto',
    }}>
      {/* Header */}
      <div style={{ textAlign: 'center', marginBottom: 28 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: '#F8FAFC', margin: 0, letterSpacing: '0.04em' }}>
          从数据结构到空间分区 <span style={{ color: ACCENT }}>学习路线</span>
        </h1>
        <p style={{ fontSize: 12, color: DIM, marginTop: 6 }}>
          你已经有图的基础 → 只需补6个知识点 → 就能完全实现最大生成树方案
        </p>
      </div>

      {/* Stage Map */}
      <div style={{ position: 'relative', marginBottom: 24 }}>
        {/* Connection line */}
        <div style={{
          position: 'absolute', top: 28, left: 32, right: 32, height: 2,
          background: 'linear-gradient(90deg, #10B981, #3B82F6, #F59E0B, #8B5CF6, #EC4899, #EF4444, #06B6D4)',
          opacity: 0.3, zIndex: 0,
        }}/>
        
        <div style={{ display: 'flex', justifyContent: 'space-between', position: 'relative', zIndex: 1 }}>
          {STAGES.map((s) => (
            <button key={s.id} onClick={() => { setSelectedStage(s.id); setExerciseStep(0); }}
              style={{
                background: selectedStage === s.id ? s.color + '22' : CARD_BG,
                border: selectedStage === s.id ? `2px solid ${s.color}` : '2px solid #334155',
                borderRadius: 12, padding: '8px 4px', cursor: 'pointer',
                width: `${100/7 - 1}%`, textAlign: 'center', transition: 'all 0.2s',
              }}>
              <div style={{ fontSize: 22 }}>{s.icon}</div>
              <div style={{ fontSize: 9, color: s.color, fontWeight: 700, marginTop: 4, lineHeight: 1.3 }}>
                {s.title}
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* Selected Stage Detail */}
      {selectedStage !== null && (
        <div style={{
          background: CARD_BG, borderRadius: 12, padding: 20,
          borderLeft: `4px solid ${STAGES[selectedStage].color}`, marginBottom: 16,
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div>
              <h2 style={{ fontSize: 17, fontWeight: 700, color: '#F8FAFC', margin: 0 }}>
                {STAGES[selectedStage].icon} {STAGES[selectedStage].title}
              </h2>
              <p style={{ fontSize: 13, color: STAGES[selectedStage].color, margin: '4px 0 0', fontWeight: 600 }}>
                {STAGES[selectedStage].subtitle}
              </p>
            </div>
          </div>

          {/* Knowledge points */}
          <div style={{ marginTop: 16 }}>
            <div style={{ fontSize: 11, color: DIM, textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 8 }}>
              {selectedStage === 0 ? '你已经掌握的' : '需要掌握的知识点'}
            </div>
            {STAGES[selectedStage].knowledge.map((k, i) => (
              <div key={i} style={{
                display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 6,
                fontSize: 13, lineHeight: 1.6,
              }}>
                <span style={{ color: STAGES[selectedStage].color, flexShrink: 0 }}>
                  {k.startsWith('★') ? '★' : '·'}
                </span>
                <span style={{ color: k.startsWith('★') ? '#FDE68A' : '#CBD5E1' }}>
                  {k.replace(/^★\s*/, '')}
                </span>
              </div>
            ))}
          </div>

          {/* Why */}
          {STAGES[selectedStage].why && (
            <div style={{
              marginTop: 14, padding: 12, background: '#0F172A', borderRadius: 8,
              borderLeft: `3px solid ${STAGES[selectedStage].color}44`,
            }}>
              <div style={{ fontSize: 10, color: STAGES[selectedStage].color, fontWeight: 700, marginBottom: 4, textTransform: 'uppercase' }}>
                为什么要学这个？
              </div>
              <div style={{ fontSize: 12, color: '#CBD5E1', lineHeight: 1.7 }}>
                {STAGES[selectedStage].why}
              </div>
            </div>
          )}

          {STAGES[selectedStage].gap && (
            <div style={{
              marginTop: 14, padding: 12, background: '#10B98111', borderRadius: 8,
              fontSize: 12, color: '#A7F3D0', lineHeight: 1.6,
            }}>
              💡 {STAGES[selectedStage].gap}
            </div>
          )}
        </div>
      )}

      {/* Exercise */}
      {currentExercise && (
        <div style={{
          background: CARD_BG, borderRadius: 12, padding: 20, marginBottom: 16,
          border: '1px solid #334155',
        }}>
          <h3 style={{ fontSize: 15, fontWeight: 700, color: ACCENT, margin: '0 0 12px' }}>
            💻 {currentExercise.title}
          </h3>

          {/* Step tabs */}
          <div style={{ display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap' }}>
            {currentExercise.steps.map((s, i) => (
              <button key={i} onClick={() => setExerciseStep(i)}
                style={{
                  padding: '5px 12px', border: 'none', borderRadius: 6, cursor: 'pointer',
                  background: exerciseStep === i ? ACCENT : '#0F172A',
                  color: exerciseStep === i ? '#0F172A' : DIM,
                  fontSize: 11, fontWeight: exerciseStep === i ? 700 : 400, fontFamily: 'inherit',
                }}>
                {s.title}
              </button>
            ))}
          </div>

          {/* Code */}
          <pre style={{
            background: '#020617', borderRadius: 8, padding: 16,
            fontSize: 11.5, lineHeight: 1.65, overflowX: 'auto',
            color: '#E2E8F0', border: '1px solid #1E293B',
            maxHeight: 420, overflowY: 'auto',
          }}>
            {currentExercise.steps[exerciseStep]?.code}
          </pre>

          {/* Explanation */}
          <div style={{
            marginTop: 12, padding: 10, background: '#0F172A', borderRadius: 8,
            fontSize: 12, color: '#FDE68A', lineHeight: 1.7,
            borderLeft: '3px solid #F59E0B44',
          }}>
            💡 {currentExercise.steps[exerciseStep]?.explanation}
          </div>
        </div>
      )}

      {/* Learning Resources */}
      <button onClick={() => setShowResources(!showResources)}
        style={{
          width: '100%', padding: '12px 16px', background: CARD_BG, border: '1px solid #334155',
          borderRadius: 10, cursor: 'pointer', color: TEXT, fontFamily: 'inherit',
          fontSize: 14, fontWeight: 600, textAlign: 'left', marginBottom: 8,
        }}>
        📚 推荐学习材料 {showResources ? '▼' : '▶'}
      </button>

      {showResources && (
        <div style={{ background: CARD_BG, borderRadius: 12, padding: 16, border: '1px solid #334155' }}>
          {RESOURCES.map((cat, ci) => (
            <div key={ci} style={{ marginBottom: ci < RESOURCES.length - 1 ? 16 : 0 }}>
              <div style={{ fontSize: 13, fontWeight: 700, color: ACCENT, marginBottom: 8 }}>
                {cat.category}
              </div>
              {cat.items.map((item, ii) => (
                <div key={ii} style={{
                  display: 'flex', gap: 10, marginBottom: 6, alignItems: 'flex-start',
                }}>
                  <span style={{
                    fontSize: 9, padding: '2px 6px', borderRadius: 4, flexShrink: 0,
                    background: item.type === 'book' ? '#3B82F622' : item.type === 'practice' ? '#10B98122' : '#F59E0B22',
                    color: item.type === 'book' ? '#93C5FD' : item.type === 'practice' ? '#6EE7B7' : '#FCD34D',
                    fontWeight: 600,
                  }}>
                    {item.type === 'book' ? '书' : item.type === 'practice' ? '刷题' : item.type === 'tool' ? '工具' : '参考'}
                  </span>
                  <div>
                    <div style={{ fontSize: 12, color: '#F1F5F9', fontWeight: 600 }}>{item.name}</div>
                    <div style={{ fontSize: 11, color: DIM }}>{item.why}</div>
                  </div>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Timeline suggestion */}
      <div style={{
        marginTop: 16, background: 'linear-gradient(135deg, #1E293B, #0F172A)',
        borderRadius: 12, padding: 18, border: '1px solid #38BDF822',
      }}>
        <div style={{ fontSize: 14, fontWeight: 700, color: '#F8FAFC', marginBottom: 10 }}>
          ⏱️ 建议学习节奏
        </div>
        <div style={{ fontSize: 12, lineHeight: 2, color: '#CBD5E1' }}>
          <span style={{color:'#10B981',fontWeight:700}}>Day 1-2</span>：并查集（实现+刷2题） → 这个最简单也最基础<br/>
          <span style={{color:'#3B82F6',fontWeight:700}}>Day 3</span>：生成树概念 + Kruskal算法（实现+手动模拟）<br/>
          <span style={{color:'#F59E0B',fontWeight:700}}>Day 4</span>：子树大小计算（DFS后序） → 在纸上画3棵树练习<br/>
          <span style={{color:'#EF4444',fontWeight:700}}>Day 5</span>：递归平衡切树（组合以上所有）→ 纯图论版本完成<br/>
          <span style={{color:'#06B6D4',fontWeight:700}}>Day 6-7</span>：学JTS基础 + 接入GIS → 完整方案跑通<br/>
          <span style={{color:'#8B5CF6',fontWeight:700}}>Day 8+</span>：用真实数据测试、调优、对比其他方案
        </div>
      </div>
    </div>
  );
}

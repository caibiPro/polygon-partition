package com.mingqing.partition.merge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Morton（Z-order）算法的"自助调试台"——不做断言，只打印中间状态，
 * 让你亲眼看见「位交错」如何把二维邻近变成一维相邻。
 * <p>
 * 跑法：./mvnw test -Dtest=MortonOrderExplorationTest
 * 然后对照下面三段输出，配合本文末尾的"动手问题"自己推。
 */
class MortonOrderExplorationTest {

    /** 用一个 4×4 网格（x,y ∈ 0..3）观察 Morton 码与访问顺序。 */
    @Test
    void printMortonZOrder() {
        int n = 4;

        // ── 第①段：单看 spreadBits 怎么"插 0" ───────────────────────────
        // spreadBits 把每一位往左拉开、中间塞 0，为交错腾出位置。
        System.out.println("=== ① spreadBits：把位拉开插 0 ===");
        for (int v = 0; v < n; v++) {
            System.out.printf("spreadBits(%d): %2d -> %d  (bin %s -> %s)%n",
                    v, v, MortonMerger.spreadBits(v),
                    bin(v, 2), bin(MortonMerger.spreadBits(v), 4));
        }

        // ── 第②段：morton 交错 x、y ─────────────────────────────────────
        // x 占偶数位、y 占奇数位，交错成一个码。高位主导大尺度位置。
        System.out.println("\n=== ② morton(x,y)：x 偶位 | y 奇位 交错 ===");
        List<int[]> points = new ArrayList<>();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                long code = MortonMerger.morton(x, y);
                points.add(new int[]{x, y, (int) code});
                System.out.printf("(x=%d,y=%d)  morton=%2d  bin=%s%n",
                        x, y, code, bin(code, 4));
            }
        }

        // ── 第③段：按 morton 码排序 = 访问顺序 ──────────────────────────
        points.sort(Comparator.comparingInt(p -> p[2]));
        System.out.println("\n=== ③ 按 morton 排序后的访问顺序 ===");
        int[][] rankGrid = new int[n][n];
        for (int rank = 0; rank < points.size(); rank++) {
            int[] p = points.get(rank);
            rankGrid[p[1]][p[0]] = rank;
            System.out.printf("第%2d 个访问: (x=%d,y=%d)%n", rank, p[0], p[1]);
        }

        // ── 第④段：把访问顺序画在网格上，看 Z / N 形 ────────────────────
        // 行从 y=3 到 y=0（让原点在左下，像数学坐标系）。
        System.out.println("\n=== ④ 访问顺序网格（沿 0→1→2… 用手指连，看是不是 Z 形）===");
        for (int y = n - 1; y >= 0; y--) {
            StringBuilder row = new StringBuilder("y=" + y + " | ");
            for (int x = 0; x < n; x++) {
                row.append(String.format("%2d ", rankGrid[y][x]));
            }
            System.out.println(row);
        }
        System.out.println("      +-----------\n        x=0  1  2  3");

        System.out.println("""

                === 动手问题（自己回答，验证你读懂了）===
                Q1. 第①段里，spreadBits(3) 的二进制是 0101 而不是 0011，为什么？
                Q2. 第②段里，morton(1,0)=1、morton(0,1)=2——为什么 y 走一步比 x 走一步
                    在码上"跳得更远"？这对"沿码排序"意味着什么？
                Q3. 第④段网格里，相邻两个序号（如 3→4）在空间上偶尔会"跳一下"。
                    找出这个跳跃发生在哪，想想 Hilbert 曲线为什么能消除它。
                """);
    }

    /** 把整数按 width 位左补零打印成二进制，便于肉眼对齐看交错。 */
    private static String bin(long v, int width) {
        String s = Long.toBinaryString(v);
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }
}

package com.mingqing.partition.merge;

import java.util.List;

/**
 * 空间装箱合并器：把若干带位置的簇合并成容量 ≤ {@code capacity} 的任务包，
 * 同时尽量让同一个包内的簇地理紧凑。
 * <p>
 * 这是一个与树切分（{@code TreePartitionAlgorithm}）完全不同的问题域：
 * <ul>
 *   <li>树切分：按<b>拓扑</b>（共享边）把<b>一个连通块</b>切小；</li>
 *   <li>空间合并：按<b>地理邻近</b>把<b>分散的小簇</b>拼大。</li>
 * </ul>
 * 两者各自独立、可单测、可替换——本接口的多个实现对应不同的空间装箱
 * 策略（单轴排序 / 条带 / Morton 曲线 / 网格 / 容量 k-means …）。
 *
 * @see <a href="">实现类各自的 javadoc 说明所用策略与优劣</a>
 */
public interface SpatialMerger {

    /**
     * 把 {@code clusters} 合并成若干包。
     * <p>
     * 不变量（所有实现都必须满足）：
     * <ol>
     *   <li>集合划分：每个成员 ID 出现且仅出现一次，不丢不重；</li>
     *   <li>容量上限：每个包的成员数 ≤ {@code capacity}。</li>
     * </ol>
     *
     * @param clusters 待合并的簇（每个簇本身已 ≤ capacity）
     * @param capacity 每个包的成员数上限
     * @return 合并后的包，每个是成员 ID 的并集
     */
    List<List<Integer>> merge(List<Cluster> clusters, int capacity);
}

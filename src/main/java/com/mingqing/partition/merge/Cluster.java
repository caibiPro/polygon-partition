package com.mingqing.partition.merge;

import java.util.List;

/**
 * 一个待合并的簇：成员 ID 集合 + 代表点坐标 (x, y)。
 * <p>
 * 故意只用裸 {@code double} 表示位置、不依赖 JTS——使空间合并算法成为
 * 一个纯粹的「带位置的装箱」问题，可用手写坐标单测，无需构造几何。
 *
 * @param members 该簇包含的 ID（在 VillagePartitioner 语境下是全村全局下标）
 * @param x       代表点 X（通常是成员质心的均值）
 * @param y       代表点 Y
 */
public record Cluster(List<Integer> members, double x, double y) {

    public int size() {
        return members.size();
    }
}

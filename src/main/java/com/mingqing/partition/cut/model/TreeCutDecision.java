package com.mingqing.partition.cut.model;

/**
 * 树切分决策结果。
 * 包含了即将被切断的边信息 (child 及其 parent)。
 */
public record TreeCutDecision(
        int childId,
        int parentId
) { }


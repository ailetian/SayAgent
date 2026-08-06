package com.hify.hify.knowledge.web;

import java.util.List;

/**
 * keyset 游标分页统一视图（K8 §6.4）。
 *
 * <p>大白话：列表翻页不用 offset（深翻页慢），而是「上一页最后一个 id」当起点继续往下翻。
 * {@code nextCursor} 是下一页起点（null 表示没有下一页），{@code hasMore} 表示是否还有更多。
 *
 * @param items       本页数据
 * @param nextCursor  下一页游标（上一页末 id 的字符串；无则为 null）
 * @param hasMore     是否还有下一页
 */
public record PageVO<T>(List<T> items, String nextCursor, boolean hasMore) {
}

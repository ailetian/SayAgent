package com.hify.hify.knowledge.web;

/**
 * 文档摘要视图对象（K11 / K9 缺口①），用于文档列表接口返回。
 *
 * <p>大白话：前端文档管理页要的「一篇文档长什么样」的精简盒子——业务 id（重新上传按钮拿它）、
 * 标题、状态、切片数、大小、更新时间。避免把数据库实体直接序列化出去（§7.11 规则37）。
 *
 * @param docId     文档业务 ID（UUID，重新上传时透传给上传接口）
 * @param title     文档标题
 * @param status    文档状态（枚举名：INDEXING / INDEXED / FAILED …）
 * @param chunkCount 切片数
 * @param sizeBytes 文件大小（字节）
 * @param updatedAt 更新时间（ISO_LOCAL_DATE_TIME 字符串，无则为 null）
 * @param jobId     最近一条索引任务 id（K11）：前端凭它调进度查询与重试端点；从未索引过则为 null。
 *                  不带这个字段，K11 的 {@code /indexing-jobs/{jobId}} 与 {@code /retry} 前端无从寻址
 */
public record DocumentSummaryVO(String docId, String title, String status, int chunkCount,
                                Long sizeBytes, String updatedAt, Long jobId) {
}

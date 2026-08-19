package com.sayagent.knowledge.web;

/**
 * 索引任务视图对象（K11 / K9 缺口③）：前端轮询上传/重传进度用。
 *
 * <p>大白话：上传或「重新上传」一篇文档后，后台会建一条索引任务（解析→切片→向量化→入库），
 * 前端拿返回的 jobId 轮询这个接口看它跑到哪一步、成功还是失败、失败死在哪环（failStage/errorCode），
 * 失败时可调重试接口从死环续跑。避免把任务实体直接序列化出去（§7.11 规则37）。
 *
 * @param id            任务自增主键
 * @param docId         关联文档自增主键（{@code document.id}）。注意<b>不是</b>业务 UUID，
 *                      与 {@code document_chunk.document_id}（UUID 字符串）不同口径，切勿混用
 * @param stage         当前阶段（UPLOAD/PARSE/CHUNK/EMBED/STORE）
 * @param status        任务状态（QUEUED/RUNNING/SUCCESS/FAILED）
 * @param progress      进度文本（如 "3/5"）
 * @param failStage     失败时的死因阶段（成功为 null）
 * @param errorCode     失败时的细分错误码（成功为 null）
 * @param errorMessage  失败原因（成功为 null）
 * @param retryCount    已重试次数
 */
public record IndexingJobVO(Long id, Long docId, String stage, String status,
                            String progress, String failStage, String errorCode,
                            String errorMessage, int retryCount) {
}

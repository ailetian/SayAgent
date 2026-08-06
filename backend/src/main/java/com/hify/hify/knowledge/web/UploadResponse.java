package com.hify.hify.knowledge.web;

import java.util.List;

/**
 * 批量上传响应（K8）。
 *
 * <p>大白话：一次上传返回「每个文件对应的文档 id + 当前索引状态」清单，前端据此轮询进度。
 * 状态语义同 {@code getDocumentStatus}：PROCESSING / DONE / FAILED / UPLOADED。
 */
public record UploadResponse(List<UploadItemResult> items) {

    /** 单条上传结果。 */
    public record UploadItemResult(String docId, String status) {
    }
}

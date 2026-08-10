package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.IndexingJob;

import java.util.Collection;
import java.util.List;

/**
 * 异步索引任务仓储（K1/K6）。派生查询支撑批量重试与进度统计。
 */
public interface IndexingJobRepository extends BaseRepository<IndexingJob> {

    /**
     * 批量一键重试：拉出某批（batch_id）内所有 FAILED 的任务。
     *
     * <p>大白话：一次上传 10 个文件算一批，任意几个挂了，前端点"批量重试"就按 batch_id
     * 把这批发失败的统统捞出来重跑，互不影响（每个 job 各自从失败节点续跑）。
     *
     * @param batchId 批次号（同一次上传共享）
     * @param status  目标状态（批量重试只认 FAILED）
     * @return 该批内处于该状态的任务列表
     */
    List<IndexingJob> findByBatchIdAndStatus(String batchId, IndexingJob.Status status);

    /**
     * 批量取一页文档的索引任务（K11 收口 K9 缺口③）。
     *
     * <p>大白话：前端文档列表要显示「这篇文档索引跑到哪一步 / 死在哪一环」，还要能点「重试索引」。
     * 但进度与重试端点都按 jobId 寻址，而上传响应只回了 docId——前端拿不到 jobId 就点不动，
     * K11 交付的两个端点等于空转。这里把整页文档的任务<b>一次 IN 查出来</b>，
     * 再在内存里按 docId 取 id 最大的那条（一篇文档可能被重传多次，只有最新那条代表当前状态）。
     * 刻意不用「循环里逐条 findTop」，那是 N+1，一页 100 条就是 100 次查询。
     *
     * @param docIds 本页文档的自增主键集合（{@code document.id}，非业务 UUID）
     * @return 这些文档的全部任务（未去重，调用方按 docId 取最新）
     */
    List<IndexingJob> findByDocIdIn(Collection<Long> docIds);

    /**
     * 取某库处于指定状态之一的索引任务（删除知识库前把在途任务置 FAILED，防孤儿向量写入，K0808）。
     */
    List<IndexingJob> findByKbIdAndStatusIn(Long kbId, Collection<IndexingJob.Status> statuses);
}

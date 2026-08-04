package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.IndexingJob;

/**
 * 异步索引任务仓储（K1/K6）。无额外查询方法，复用 {@link BaseRepository} 通用 CRUD（含软删语义）；
 * 逐节点进度 / 批量重试的派生查询由 K6 按需在子接口补充。
 */
public interface IndexingJobRepository extends BaseRepository<IndexingJob> {
}

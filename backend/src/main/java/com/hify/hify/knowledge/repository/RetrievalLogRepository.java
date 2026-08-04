package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.RetrievalLog;

/**
 * 检索日志仓储（K1/K5）。无额外查询方法，复用 {@link BaseRepository} 通用 CRUD（含软删语义）；
 * 拒答率 / 命中质量统计由 K5 按需在子接口补充。
 */
public interface RetrievalLogRepository extends BaseRepository<RetrievalLog> {
}

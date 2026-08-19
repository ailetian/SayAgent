package com.sayagent.knowledge.repository;

import com.sayagent.common.base.BaseRepository;
import com.sayagent.knowledge.entity.RetrievalLog;

import java.util.List;

/**
 * 检索日志仓储（K1/K5）。无额外查询方法，复用 {@link BaseRepository} 通用 CRUD（含软删语义）；
 * 拒答率 / 命中质量统计由 K5 按需在子接口补充。
 */
public interface RetrievalLogRepository extends BaseRepository<RetrievalLog> {

    /**
     * 取某知识库最近若干条检索日志（K8 体检·命中质量 / 响应速度用）。
     *
     * @param kbId 知识库 id
     * @return 最近 50 条（按 id 倒序）
     */
    List<RetrievalLog> findTop50ByKbIdOrderByIdDesc(Long kbId);
}

package com.sayagent.knowledge.repository;

import com.sayagent.common.base.BaseRepository;
import com.sayagent.knowledge.entity.EvalDataset;

/**
 * 评测集仓储（K1/K10）。按库取题查询供题集打分门禁使用（§6.4 keyset 的父维度过滤思想：
 * 评测集必须按 kbId 收窄，杜绝跨库题集混入同一份报告）。
 */
public interface EvalDatasetRepository extends BaseRepository<EvalDataset> {

    /** 取某库全部评测题（软删由 {@link BaseRepository} 的 {@code @SQLRestriction} 自动过滤）。 */
    java.util.List<EvalDataset> findByKbId(Long kbId);
}

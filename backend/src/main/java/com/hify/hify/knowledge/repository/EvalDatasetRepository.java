package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.EvalDataset;

/**
 * 评测集仓储（K1/K10）。无额外查询方法，复用 {@link BaseRepository} 通用 CRUD（含软删语义）；
 * 题集打分门禁的按库取题查询由 K10 按需在子接口补充。
 */
public interface EvalDatasetRepository extends BaseRepository<EvalDataset> {
}

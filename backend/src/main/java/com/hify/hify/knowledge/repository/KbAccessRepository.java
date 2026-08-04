package com.hify.hify.knowledge.repository;

import com.hify.hify.common.base.BaseRepository;
import com.hify.hify.knowledge.entity.KbAccess;
import com.hify.hify.knowledge.entity.KbAccessTargetType;

import java.util.List;
import java.util.Optional;

/**
 * 知识库访问授权仓储（RBAC，M5 整改扩展）。
 * 软删过滤由 {@code @SQLRestriction} 在实体上统一处理，查询默认只看未删记录。
 */
public interface KbAccessRepository extends BaseRepository<KbAccess> {

    /** 某知识库的全部有效授权（已按软删过滤）。 */
    List<KbAccess> findByKbId(Long kbId);

    /** 判断某知识库是否已存在指定 (targetType, targetId) 的授权（去重用）。 */
    boolean existsByKbIdAndTargetTypeAndTargetId(Long kbId, KbAccessTargetType targetType, String targetId);

    /** 按知识库 + 授权记录 id 取一条（用于撤销前校验归属），已按软删过滤。 */
    Optional<KbAccess> findByKbIdAndId(Long kbId, Long id);
}

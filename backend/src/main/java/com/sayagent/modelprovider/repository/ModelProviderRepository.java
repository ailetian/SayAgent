package com.sayagent.modelprovider.repository;

import com.sayagent.common.base.BaseRepository;
import com.sayagent.modelprovider.domain.enums.ProviderType;
import com.sayagent.modelprovider.entity.ModelProvider;

import java.util.List;
import java.util.Optional;

/**
 * 模型提供商数据访问。BaseRepository 已提供软删除基础的 JpaRepository 能力（§6.1）。
 *
 * <p>派生查询（§7.1 命名 / §3.2 仓库，Spring Data 自动实现）：
 * <ul>
 *   <li>{@link #findByProviderType} 按厂商类型查；</li>
 *   <li>{@link #findAllByEnabledTrueOrderBySortOrderAsc} 查启用模型并按 sortOrder 升序；</li>
 *   <li>{@link #findByDefaultModelTrue} 查默认模型。</li>
 * </ul>
 */
public interface ModelProviderRepository extends BaseRepository<ModelProvider> {

    /** 按厂商类型查询（如查所有 OPENAI 配置）。 */
    List<ModelProvider> findByProviderType(ProviderType providerType);

    /** 查所有启用模型，按 sortOrder 升序（越小越靠前）。 */
    List<ModelProvider> findAllByEnabledTrueOrderBySortOrderAsc();

    /** 查默认模型（全公司唯一默认）。 */
    Optional<ModelProvider> findByDefaultModelTrue();
}

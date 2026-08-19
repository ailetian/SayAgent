package com.sayagent.modelprovider.entity;

import com.sayagent.common.base.BaseEntity;
import com.sayagent.modelprovider.domain.enums.ProviderType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 模型提供商配置（一张表登记全公司可用的各家大模型）。
 *
 * <p>大白话：相当于"模型花名册"——管理员填好名字、接口地址、秘钥、类型后，全公司共用。
 * 四字段 id/created_at/updated_at/deleted 由 {@link BaseEntity} 提供（§6.1）。
 * 秘钥 secret 只存后端、绝不序列化返前端（脱敏在 T5 做，§7.11）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE model_provider SET deleted = 1 WHERE id = ?")
@Entity
@Table(name = "model_provider")
public class ModelProvider extends BaseEntity {

    /** 展示名（如 "公司OpenAI"）。 */
    @Column(nullable = false, length = 64)
    private String name;

    /** 接口地址（如 https://api.openai.com/v1）。 */
    @Column(name = "api_url", nullable = false, length = 255)
    private String apiUrl;

    /** 秘钥：仅后端读取，明文永不返前端（§7.11）。 */
    @Column(length = 255)
    private String secret;

    /** 厂商类型：OPENAI/CLAUDE/GEMINI/OLLAMA，存字符串（§4.5）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ProviderType providerType;

    /** 默认模型名（如 gpt-4o / claude-3-5-sonnet / gemini-1.5-pro / llama3），路由调用时带给 Client（§3.5 ProviderConfig.model）。 */
    @Column(name = "model", length = 64)
    private String model;

    /** 是否启用：true=可用，false=停用（派生查询 findAllByEnabledTrue... 用）。 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 是否默认模型：true=默认，false=否（派生查询 findByDefaultModelTrue... 用；§7.1 规则5 布尔不用 is 前缀）。 */
    @Column(name = "is_default", nullable = false)
    private Boolean defaultModel = false;

    /** 排序权重：越小越靠前（派生查询 findAllByEnabledTrueOrderBySortOrderAsc 用）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}

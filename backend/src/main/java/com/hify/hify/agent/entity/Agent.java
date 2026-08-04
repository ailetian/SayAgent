package com.hify.hify.agent.entity;

import com.hify.hify.common.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 配置实体（M4/T1，§3.2 后端包结构）。
 *
 * <p>大白话：一张「智能体名片」——名字、人设(system_prompt)、默认用哪家模型厂商、调参(temperature/top_p/...)。
 * 它只存「配置」，不存对话历史（那是 M6 的 message 表管的事）。
 *
 * <p>软删除：必须在本 {@code @Entity} 上显式声明 {@code @SQLRestriction} 与 {@code @SQLDelete}
 * （{@code BaseEntity} 的 {@code @MappedSuperclass} 注解不会传播到子类，见 §6.1 实现纪律）。
 * {@code secret} / {@code userPassword} 仅后端调用外部 Agent 时使用，<b>绝不</b>经 VO 返回前端（§7.11）。
 */
@Entity
@Table(name = "agent")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `agent` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class Agent extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, columnDefinition = "varchar(500) default ''")
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    /** 默认模型厂商 id（来自模型管理 model_provider.id）；跨模块通过 ModelService 校验存在性。 */
    @Column(name = "model_provider_id", nullable = false)
    private Long modelProviderId;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(nullable = false, columnDefinition = "varchar(500) default ''")
    private String secret;

    @Column(name = "user_password", nullable = false, columnDefinition = "varchar(500) default ''")
    private String userPassword;

    @Column(nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean enabled;

    @Column(name = "is_default_agent", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean defaultAgent;

    @Column(name = "sort_order", nullable = false, columnDefinition = "int default 0")
    private Integer sortOrder;

    @Column(precision = 3, scale = 2, columnDefinition = "decimal(3,2) default 0.70")
    private BigDecimal temperature;

    @Column(name = "top_p", precision = 3, scale = 2, columnDefinition = "decimal(3,2) default 1.00")
    private BigDecimal topP;

    @Column(name = "max_tokens", columnDefinition = "int default 2048")
    private Integer maxTokens;

    @Column(name = "max_context_tokens", columnDefinition = "int default 8192")
    private Integer maxContextTokens;

    /**
     * 知识库引用 id 列表（M4/T3）。经 {@link RefsJsonConverter} 在 {@code List<Long>}
     * 与数据库 JSON 数组文本（如 '[1,2]'）之间互转，存于 knowledge_refs 列。
     */
    @Convert(converter = RefsJsonConverter.class)
    @Column(name = "knowledge_refs", columnDefinition = "text")
    private List<Long> knowledgeRefs = new ArrayList<>();

    /**
     * 工具引用 id 列表（M4/T3）。同上，存于 tool_refs 列。
     */
    @Convert(converter = RefsJsonConverter.class)
    @Column(name = "tool_refs", columnDefinition = "text")
    private List<Long> toolRefs = new ArrayList<>();
}

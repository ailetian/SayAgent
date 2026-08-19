package com.sayagent.modelprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sayagent.modelprovider.domain.enums.ProviderType;
import com.sayagent.modelprovider.entity.ModelProvider;

/**
 * 模型提供商对外视图对象（§3.4 分层纪律：Controller 只往外吐 VO，不吐实体）。
 *
 * <p>大白话：管理后台查看/维护模型时，用这个盒子代替 {@code ModelProvider} 实体返回。
 * 唯一铁律——<b>秘钥 secret 绝不明文回前端</b>：字段打 {@code @JsonIgnore}，
 * Jackson 序列化直接跳过它（仿 M2 {@code UserVO} 对 password 的做法，§7.11 规则37）。
 *
 * @param id           主键
 * @param name         展示名
 * @param apiUrl       接口地址
 * @param providerType 厂商类型
 * @param model        默认模型名
 * @param enabled      是否启用
 * @param defaultModel 是否默认
 * @param sortOrder    排序权重
 * @param secret       秘钥（仅后端内部构造，序列化时丢弃，§7.11）
 */
public record ModelProviderVO(Long id,
                              String name,
                              String apiUrl,
                              ProviderType providerType,
                              String model,
                              Boolean enabled,
                              Boolean defaultModel,
                              Integer sortOrder,
                              @JsonIgnore String secret) {

    /** 实体 → VO（秘钥原样带入但被 @JsonIgnore 屏蔽，§7.11）。 */
    public static ModelProviderVO from(ModelProvider p) {
        return new ModelProviderVO(
                p.getId(),
                p.getName(),
                p.getApiUrl(),
                p.getProviderType(),
                p.getModel(),
                p.getEnabled(),
                p.getDefaultModel(),
                p.getSortOrder(),
                p.getSecret());
    }
}

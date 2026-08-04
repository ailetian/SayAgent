package com.hify.hify.modelprovider.dto;

import com.hify.hify.modelprovider.domain.enums.ProviderType;

/**
 * 修改模型请求（§3.5 入参契约）。
 *
 * <p>大白话：管理员改某张模型配置。所有字段均可空——传了才改，没传保留原值（部分更新）。
 * secret 若传空字符串表示清空秘钥；为支持「不修改秘钥」，省略该字段（null）时服务层保留原秘钥。
 */
public record ProviderUpdateRequest(

        String name,

        String apiUrl,

        /** 秘钥：传 null 表示保留原秘钥，传非 null 表示更新（永不明文返前端，§7.11）。 */
        String secret,

        ProviderType providerType,

        String model,

        Boolean enabled,

        Boolean defaultModel,

        Integer sortOrder) {
}

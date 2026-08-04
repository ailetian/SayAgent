package com.hify.hify.modelprovider.dto;

import com.hify.hify.modelprovider.domain.enums.ProviderType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新增模型请求（§3.5 入参契约）。
 *
 * <p>大白话：管理员填一张「新模型登记表」。name/apiUrl/type 必填（@NotBlank/@NotNull 由 Controller 的
 * {@code @Valid} 拦在门外）；secret 来自请求体、明文只进库不打印（§7.11）；其余字段可选，缺省走实体默认值。
 */
public record ProviderCreateRequest(

        @NotBlank(message = "名称不能为空")
        String name,

        @NotBlank(message = "接口地址不能为空")
        String apiUrl,

        /** 秘钥：仅后端读取，永不序列化返前端（§7.11）。 */
        String secret,

        @NotNull(message = "厂商类型不能为空")
        ProviderType providerType,

        String model,

        Boolean enabled,

        Boolean defaultModel,

        Integer sortOrder) {
}

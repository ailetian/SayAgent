package com.hify.hify.knowledge.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 透传 JSON 字符串转换器（K1）。
 *
 * <p>大白话：{@code rag_config} 在 Java 里就是一段 JSON 文本（{@code String}），在 MySQL 里存成 JSON 列。
 * 用这个转换器让 Hibernate「原样」存取——既享受 JSON 列的类型，又避免 Hibernate 把 {@code String} 当普通
 * 字符串再用 Jackson 包一层引号导致「双重编码」（即存进库变成 {@code "\"{\\\"a\\\":1}\""} 这种坏数据）。
 * K2 负责把这段文本解析成 {@code RagConfig} 参数对象。
 *
 * <p>注意：不能用 {@code @JdbcTypeCode(SqlTypes.JSON)} 直接标在 {@code String} 上——那会触发 Jackson 序列化，
 * 把字符串再包一层引号（双重编码），详见上文。本类是仓库既有 {@code RefsJsonConverter} 同思路的极简透传版。
 */
@Converter(autoApply = false)
public class JsonRawConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}

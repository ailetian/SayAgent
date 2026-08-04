package com.hify.hify.agent.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * List&lt;Long&gt; ↔ JSON 数组字符串 的 JPA 属性转换器（M4/T3，知识库/工具引用）。
 *
 * <p>大白话：把「引用 id 列表」在 Java 侧用 {@code List<Long>} 操作，落库时存成一段文本
 * {@code '[1,2,3]'}；读出来再还原成 List。空集合 / 空串 / null 统一转成空 List，
 * 绝不返回 null（避免下游 NPE）。
 */
@Converter
public class RefsJsonConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(List<Long> refs) {
        if (refs == null || refs.isEmpty()) {
            return "[]";
        }
        return "[" + refs.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    @Override
    public List<Long> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || "[]".equals(dbData.trim())) {
            return Collections.emptyList();
        }
        String body = dbData.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(body.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}

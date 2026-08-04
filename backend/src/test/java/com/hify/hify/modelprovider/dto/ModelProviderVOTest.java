package com.hify.hify.modelprovider.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.hify.modelprovider.domain.enums.ProviderType;
import com.hify.hify.modelprovider.entity.ModelProvider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ModelProviderVO 秘钥隔离点检（§7.11 规则37）。
 *
 * <p>大白话：把带秘钥的实体转成 VO 再序列化，JSON 里永远不能出现 apiKey / secret 字段或秘钥明文，
 * 否则前端就能拿到厂商秘钥。本测试把它锁死。
 *
 * <p>命名遵循 test方法_场景_预期（AGENTS.md §7.10 规则34）。
 */
class ModelProviderVOTest {

    @Test
    void testModelProviderVO_serialize_excludesApiKey() throws Exception {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setName("gpt");
        p.setApiUrl("https://api.openai.com/v1");
        p.setSecret("sk-topsecret-abc");
        p.setProviderType(ProviderType.OPENAI);
        p.setModel("gpt-4o");
        p.setEnabled(true);
        p.setDefaultModel(false);
        p.setSortOrder(0);

        ModelProviderVO vo = ModelProviderVO.from(p);
        String json = new ObjectMapper().writeValueAsString(vo);

        assertFalse(json.contains("apiKey"), "响应 JSON 不应含 apiKey 字段");
        assertFalse(json.contains("secret"), "响应 JSON 不应含 secret 字段");
        assertFalse(json.contains("sk-topsecret-abc"), "响应 JSON 不应含秘钥明文");
    }
}

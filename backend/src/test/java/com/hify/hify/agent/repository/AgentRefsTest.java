package com.hify.hify.agent.repository;

import com.hify.hify.agent.entity.Agent;
import com.hify.hify.agent.repository.AgentRepository;
import com.hify.hify.modelprovider.domain.enums.ProviderType;
import com.hify.hify.modelprovider.entity.ModelProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent refs 字段往返点检（M4/T3，§7.10 真库切片测试）。
 *
 * <p>大白话：存一条带 knowledgeRefs/toolRefs 的 Agent，读出来引用列表应与存入的一致，
 * 验证 RefsJsonConverter（List&lt;Long&gt; ↔ '[1,2,3]'）在真实 MySQL 上往返无误。
 * agent 表挂了外键 model_provider_id → model_provider(id)，故每个用例先插一条 provider 撑住外键。
 * 命名遵循 {@code test方法_场景_预期}（AGENTS.md §7.10 规则34）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.locations=classpath:db/mysql/migration"
})
class AgentRefsTest {

    @Autowired
    private AgentRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void testSaveAndLoad_refsRoundTrip_preserved() {
        Long pid = seedProvider();
        Agent a = buildAgent(pid);
        a.setKnowledgeRefs(List.of(101L, 102L));
        a.setToolRefs(List.of(201L));
        Agent saved = repository.saveAndFlush(a);

        Optional<Agent> loaded = repository.findById(saved.getId());
        assertEquals(List.of(101L, 102L), loaded.get().getKnowledgeRefs(),
                "knowledgeRefs 应原样往返");
        assertEquals(List.of(201L), loaded.get().getToolRefs(),
                "toolRefs 应原样往返");
    }

    @Test
    void testSave_emptyRefs_persistedAsEmptyList() {
        Long pid = seedProvider();
        Agent a = buildAgent(pid); // 引用字段默认空 List
        Agent saved = repository.saveAndFlush(a);

        Optional<Agent> loaded = repository.findById(saved.getId());
        assertEquals(List.of(), loaded.get().getKnowledgeRefs());
        assertEquals(List.of(), loaded.get().getToolRefs());
    }

    /** 撑住 agent 的外键：插一条最小可用 model_provider 并返回其(自增)id。 */
    private Long seedProvider() {
        ModelProvider p = new ModelProvider();
        p.setName("p-" + System.nanoTime());
        p.setApiUrl("http://localhost");
        p.setSecret("");
        p.setProviderType(ProviderType.OPENAI);
        p.setModel("gpt-4o");
        p.setEnabled(true);
        p.setDefaultModel(false);
        p.setSortOrder(0);
        return testEntityManager.persistAndFlush(p).getId();
    }

    private Agent buildAgent(Long providerId) {
        Agent a = new Agent();
        a.setName("refs-agent");
        a.setDescription("desc");
        a.setSystemPrompt("sys");
        a.setModelProviderId(providerId);
        a.setModel("gpt-4o");
        a.setSecret("");
        a.setUserPassword("");
        a.setEnabled(true);
        a.setDefaultAgent(false);
        a.setSortOrder(0);
        a.setTemperature(BigDecimal.valueOf(0.70));
        a.setTopP(BigDecimal.valueOf(1.00));
        a.setMaxTokens(2048);
        a.setMaxContextTokens(8192);
        return a;
    }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentRepository 仓储点检（§7.10 真库切片测试）。
 *
 * <p>大白话：用 @DataJpaTest 把 JPA 这一层单独拎出来跑，连真实 MySQL（Flyway 自动建好 agent 表），
 * 只验证四条派生查询 + 软删除。测试在事务内执行，结束自动回滚，不污染业务数据。
 * agent 表上挂了外键 model_provider_id → model_provider(id)，故每个用例先插一条 model_provider 撑住外键。
 * 命名遵循 {@code test方法_场景_预期}（CLAUDE.md §7.10 规则34）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.locations=classpath:db/mysql/migration"
})
class AgentRepositoryTest {

    @Autowired
    private AgentRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void testFindByModelProviderId_returnsOnlyThatProvider() {
        Long p1 = seedProvider();
        Long p2 = seedProvider();
        Agent a1 = buildAgent("gpt-agent", p1, "gpt-4o", true, false, 0);
        Agent a2 = buildAgent("claude-agent", p2, "claude-3", true, false, 0);
        repository.saveAll(List.of(a1, a2));

        List<Agent> list = repository.findByModelProviderId(p1);
        assertEquals(1, list.size());
        assertEquals("gpt-agent", list.get(0).getName());
    }

    @Test
    void testFindAllByEnabledTrueOrderBySortOrderAsc_returnsEnabledSorted() {
        Long p1 = seedProvider();
        Agent disabled = buildAgent("off", p1, "m1", false, false, 0);
        Agent low = buildAgent("low", p1, "m2", true, false, 5);
        Agent high = buildAgent("high", p1, "m3", true, false, 1);
        repository.saveAll(List.of(disabled, low, high));

        List<Agent> enabled = repository.findAllByEnabledTrueOrderBySortOrderAsc();
        assertEquals(2, enabled.size());
        assertEquals("high", enabled.get(0).getName()); // sortOrder 1 在前
        assertEquals("low", enabled.get(1).getName());   // sortOrder 5 在后
    }

    @Test
    void testFindByDefaultAgentTrue_returnsDefaultAgent() {
        Long p1 = seedProvider();
        Agent normal = buildAgent("normal", p1, "m1", true, false, 1);
        Agent def = buildAgent("default", p1, "m2", true, true, 0);
        repository.saveAll(List.of(normal, def));

        Optional<Agent> defaultOpt = repository.findByDefaultAgentTrue();
        assertTrue(defaultOpt.isPresent());
        assertEquals("default", defaultOpt.get().getName());
    }

    @Test
    void testFindByModelProviderIdAndName_findsDuplicate() {
        Long p1 = seedProvider();
        Agent a = buildAgent("dup", p1, "m1", true, false, 0);
        repository.save(a);

        Optional<Agent> found = repository.findByModelProviderIdAndName(p1, "dup");
        assertTrue(found.isPresent());
        assertEquals("m1", found.get().getModel());
    }

    @Test
    void testDelete_softDeletesRow_keepsRowInTable() {
        Long p1 = seedProvider();
        Agent saved = repository.save(buildAgent("del", p1, "m1", true, false, 0));
        Long id = saved.getId();

        repository.delete(saved);

        assertTrue(repository.findById(id).isEmpty(), "@SQLRestriction 应隐藏已软删行");
        Object cnt = testEntityManager.getEntityManager()
                .createNativeQuery("select count(*) from agent where id = ?")
                .setParameter(1, id).getSingleResult();
        assertEquals(1, Integer.parseInt(cnt.toString()), "delete 应转为 UPDATE，行仍在表中（§6.1 不真删数据）");
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

    private Agent buildAgent(String name, Long providerId, String model,
                             boolean enabled, boolean isDefault, int sortOrder) {
        Agent a = new Agent();
        a.setName(name);
        a.setDescription("desc-" + name);
        a.setSystemPrompt("sys-" + name);
        a.setModelProviderId(providerId);
        a.setModel(model);
        a.setSecret("");
        a.setUserPassword("");
        a.setEnabled(enabled);
        a.setDefaultAgent(isDefault);
        a.setSortOrder(sortOrder);
        a.setTemperature(BigDecimal.valueOf(0.70));
        a.setTopP(BigDecimal.valueOf(1.00));
        a.setMaxTokens(2048);
        a.setMaxContextTokens(8192);
        return a;
    }
}

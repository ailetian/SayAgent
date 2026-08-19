package com.sayagent.modelprovider.repository;

import com.sayagent.modelprovider.domain.enums.ProviderType;
import com.sayagent.modelprovider.entity.ModelProvider;
import com.sayagent.modelprovider.repository.ModelProviderRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelProviderRepository 仓储点检（§7.10 真库切片测试）。
 *
 * <p>大白话：用 @DataJpaTest 把 JPA 这一层单独拎出来跑，连真实 MySQL（application.yml 配置，
 * Flyway 自动建好 model_provider 表），只验证三条派生查询。测试在事务内执行，结束自动回滚，
 * 不会污染业务数据。
 *
 * <p>命名遵循 {@code test方法_场景_预期}（AGENTS.md §7.10 规则34）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.locations=classpath:db/mysql/migration"
})
class ModelProviderRepositoryTest {

    @Autowired
    private ModelProviderRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void testFindByProviderType_returnsMatchingProvider() {
        ModelProvider openai = buildProvider("gpt", "https://api.openai.com", ProviderType.OPENAI, true, false, 1);
        ModelProvider claude = buildProvider("claude", "https://api.anthropic.com", ProviderType.CLAUDE, true, false, 2);
        repository.saveAll(List.of(openai, claude));

        List<ModelProvider> openaiList = repository.findByProviderType(ProviderType.OPENAI);
        assertEquals(1, openaiList.size());
        assertEquals("gpt", openaiList.get(0).getName());
    }

    @Test
    void testFindAllByEnabledTrueOrderBySortOrderAsc_returnsOnlyEnabledSorted() {
        ModelProvider disabled = buildProvider("off", "https://x.com", ProviderType.OLLAMA, false, false, 0);
        ModelProvider low = buildProvider("low", "https://y.com", ProviderType.OLLAMA, true, false, 5);
        ModelProvider high = buildProvider("high", "https://z.com", ProviderType.OLLAMA, true, false, 1);
        repository.saveAll(List.of(disabled, low, high));

        List<ModelProvider> enabled = repository.findAllByEnabledTrueOrderBySortOrderAsc();
        assertEquals(2, enabled.size());
        assertEquals("high", enabled.get(0).getName()); // sortOrder 1 排在前
        assertEquals("low", enabled.get(1).getName());   // sortOrder 5 排在后
    }

    @Test
    void testFindByProviderType_excludesSoftDeletedProvider() {
        ModelProvider active = buildProvider("active", "https://a.com", ProviderType.OPENAI, true, false, 1);
        ModelProvider removed = buildProvider("removed", "https://b.com", ProviderType.OPENAI, true, false, 2);
        removed.setDeleted(true); // 模拟软删除
        repository.saveAll(List.of(active, removed));

        List<ModelProvider> list = repository.findByProviderType(ProviderType.OPENAI);
        assertEquals(1, list.size(), "@SQLRestriction(deleted=0) 应过滤已软删行");
        assertEquals("active", list.get(0).getName());
    }

    @Test
    void testDelete_softDeletesRow_keepsRowInTable() {
        ModelProvider saved = repository.save(buildProvider("del", "https://d.com", ProviderType.OPENAI, true, false, 1));
        Long id = saved.getId();

        repository.delete(saved);

        assertTrue(repository.findById(id).isEmpty(), "@Where 应隐藏已软删行");
        Object cnt = testEntityManager.getEntityManager()
                .createNativeQuery("select count(*) from model_provider where id = ?")
                .setParameter(1, id).getSingleResult();
        assertEquals(1, Integer.parseInt(cnt.toString()), "delete 应转为 UPDATE，行仍在表中（§6.1 不真删数据）");
    }

    @Test
    void testFindByDefaultModelTrue_returnsDefaultProvider() {
        ModelProvider normal = buildProvider("normal", "https://x.com", ProviderType.GEMINI, true, false, 1);
        ModelProvider def = buildProvider("default", "https://y.com", ProviderType.OPENAI, true, true, 0);
        repository.saveAll(List.of(normal, def));

        Optional<ModelProvider> defaultOpt = repository.findByDefaultModelTrue();
        assertTrue(defaultOpt.isPresent());
        assertEquals("default", defaultOpt.get().getName());
    }

    private ModelProvider buildProvider(String name, String apiUrl, ProviderType type,
                                        boolean enabled, boolean isDefault, int sortOrder) {
        ModelProvider p = new ModelProvider();
        p.setName(name);
        p.setApiUrl(apiUrl);
        p.setSecret("test-secret");
        p.setProviderType(type);
        p.setEnabled(enabled);
        p.setDefaultModel(isDefault);
        p.setSortOrder(sortOrder);
        return p;
    }
}

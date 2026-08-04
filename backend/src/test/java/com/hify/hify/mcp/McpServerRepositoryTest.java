package com.hify.hify.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpServerRepository 仓储点检（M7/T1，§7.10 真库切片测试 + §6.1 软删实现纪律）。
 *
 * <p>大白话：用 @DataJpaTest 把 JPA 这一层单独拎出来跑，连真实 MySQL（application.yml 配置，
 * Flyway 自动建好 mcp_server 表），只验证「软删除真生效」。测试在事务内执行，结束自动回滚，
 * 不会污染业务数据。
 *
 * <p>为什么必须验：软删注解放 {@code @MappedSuperclass}（BaseEntity）上不会传播到子类查询，
 * 必须放在具体 {@code @Entity}（McpServer）上才真生效（代码审核坑位5）。本测试即「实跑验证」：
 * 断言 findAll 过滤掉 deleted=1 的行、delete 把行转成 UPDATE(deleted=1) 而非真删。
 *
 * <p>命名遵循 {@code test方法_场景_预期}（CLAUDE.md §7.10 规则34）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.locations=classpath:db/mysql/migration"
})
class McpServerRepositoryTest {

    @Autowired
    private McpServerRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void testFindAll_excludesSoftDeletedServer() {
        McpServer active = buildServer("active", "http://a.internal:8080/mcp", "SSE", 1);
        McpServer removed = buildServer("removed", "http://b.internal:8080/mcp", "HTTP", 1);
        removed.setDeleted(true); // 模拟软删除
        repository.saveAll(List.of(active, removed));

        // findAll 走 @SQLRestriction(deleted = 0)，应只返回未删行
        List<McpServer> list = repository.findAll();
        assertEquals(1, list.size(), "@SQLRestriction(deleted=0) 应过滤已软删行");
        assertEquals("active", list.get(0).getName());
    }

    @Test
    void testDelete_softDeletesRow_keepsRowInTable() {
        McpServer saved = repository.save(buildServer("del", "http://d.internal:8080/mcp", "STDIO", 1));
        Long id = saved.getId();

        repository.delete(saved);

        assertTrue(repository.findAll().isEmpty(), "@SQLRestriction 应隐藏已软删行");
        Object cnt = testEntityManager.getEntityManager()
                .createNativeQuery("select count(*) from mcp_server where id = ?")
                .setParameter(1, id).getSingleResult();
        assertEquals(1, Integer.parseInt(cnt.toString()), "delete 应转为 UPDATE，行仍在表中（§6.1 不真删数据）");
    }

    private McpServer buildServer(String name, String address, String type, int status) {
        McpServer s = new McpServer();
        s.setName(name);
        s.setAddress(address);
        s.setType(type);
        s.setStatus(status);
        return s;
    }
}

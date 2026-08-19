package com.sayagent.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserRepository 仓储点检（§7.10 真库切片测试）。
 *
 * <p>大白话：用 @DataJpaTest 把 JPA 这一层单独拎出来跑，连真实 MySQL（application.yml 配置，
 * Flyway 自动建好 user 表），验证软删除真生效。测试在事务内执行，结束自动回滚，不污染业务数据。
 *
 * <p>命名遵循 {@code test方法_场景_预期}（AGENTS.md §7.10 规则34）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.locations=classpath:db/mysql/migration"
})
class UserRepositoryTest {

    @Autowired
    private UserRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void testFindByUsernameAndDeletedFalse_returnsMatchingUser() {
        User alice = buildUser("alice", "ENC(alice-pwd)");
        User bob = buildUser("bob", "ENC(bob-pwd)");
        repository.saveAll(List.of(alice, bob));

        Optional<User> found = repository.findByUsernameAndDeletedFalse("alice");
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
    }

    @Test
    void testFindAll_excludesSoftDeletedUser() {
        User active = buildUser("active_user", "ENC(a-pwd)");
        User removed = buildUser("removed_user", "ENC(r-pwd)");
        repository.saveAll(List.of(active, removed));
        removed.setDeleted(true); // 模拟软删除
        repository.save(removed);

        // @SQLRestriction(deleted=0) 应过滤已软删行：查询 SQL 带 deleted=0，DB 不返回它，
        // 绕开一级缓存陷阱（findById 在同事务会命中 L1 缓存，不能验证过滤）。
        List<User> all = repository.findAll();
        boolean hasRemoved = all.stream()
                .anyMatch(u -> "removed_user".equals(u.getUsername()));
        assertFalse(hasRemoved, "@SQLRestriction(deleted=0) 应过滤已软删行（坑位5 验证）");
    }

    @Test
    void testDelete_softDeletesRow_keepsRowInTable() {
        User saved = repository.save(buildUser("delme", "ENC(del-pwd)"));
        Long id = saved.getId();

        repository.delete(saved);

        assertTrue(repository.findById(id).isEmpty(), "@SQLRestriction 应隐藏已软删行");
        Object cnt = testEntityManager.getEntityManager()
                .createNativeQuery("select count(*) from `user` where id = ?")
                .setParameter(1, id).getSingleResult();
        assertEquals(1, Integer.parseInt(cnt.toString()),
                "delete 应转为 UPDATE，行仍在表中（§6.1 不真删数据）");
    }

    private User buildUser(String username, String password) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(password);
        u.setRole(UserRole.USER);
        return u;
    }
}

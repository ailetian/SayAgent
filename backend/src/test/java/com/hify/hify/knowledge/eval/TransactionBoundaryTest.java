package com.hify.hify.knowledge.eval;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P4 事务边界单测（K10）：索引流水线必须"每步各自短事务"，绝不用一个长事务裹住整条链路
 * （embedding 慢、PG 连接在事务里会拖垮并发）；而切片"删旧+插新"必须落在 {@code pgTransactionManager}
 * 这一个 pg 本地事务里，保证原子（防半套 chunk）。用反射锁死这两个约束（无需连真库）。
 */
class TransactionBoundaryTest {

    @Test
    void indexingJobService_hasNoTransactionalAnywhere() throws Exception {
        Class<?> cls = Class.forName("com.hify.hify.knowledge.service.IndexingJobService");
        assertNull(cls.getAnnotation(Transactional.class), "IndexingJobService 类不得标 @Transactional");

        for (Method m : cls.getDeclaredMethods()) {
            assertNull(m.getAnnotation(Transactional.class),
                    "IndexingJobService." + m.getName() + " 不得标 @Transactional（每步各自短事务）");
        }
        for (Class<?> itf : cls.getInterfaces()) {
            for (Method m : itf.getDeclaredMethods()) {
                assertNull(m.getAnnotation(Transactional.class),
                        "接口 " + itf.getSimpleName() + "." + m.getName() + " 不得标 @Transactional");
            }
        }
        Class<?> s = cls.getSuperclass();
        while (s != null && !Object.class.equals(s)) {
            for (Method m : s.getDeclaredMethods()) {
                assertNull(m.getAnnotation(Transactional.class),
                        "父类 " + s.getSimpleName() + "." + m.getName() + " 不得标 @Transactional");
            }
            s = s.getSuperclass();
        }
    }

    @Test
    void documentChunkReplaceChunks_isTransactionalOnPgManager() throws Exception {
        Class<?> repoCls = Class.forName("com.hify.hify.knowledge.repository.DocumentChunkRepository");
        Method m = repoCls.getMethod("replaceChunks", String.class, Long.class, List.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "DocumentChunkRepository.replaceChunks 必须标 @Transactional（原子删插）");
        // @Transactional("pgTransactionManager") 写的是 value 别名，transactionManager() 反射为空，需解析别名
        String tm = tx.transactionManager();
        if (tm == null || tm.isEmpty()) {
            tm = tx.value();
        }
        assertEquals("pgTransactionManager", tm);
    }
}

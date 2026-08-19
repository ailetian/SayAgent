package com.sayagent.conversation.repository;

import com.sayagent.conversation.entity.Conversation;
import com.sayagent.conversation.entity.Conversation.ConversationStatus;
import com.sayagent.conversation.entity.ConversationLog;
import com.sayagent.conversation.entity.Message;
import com.sayagent.conversation.entity.Message.MessageRole;
import com.sayagent.conversation.entity.Message.MessageStatus;
import com.sayagent.user.User;
import com.sayagent.user.UserRole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1 验收：GIVEN 三张表已随 Flyway 建好 WHEN 落库 conversation+user/assistant message THEN 能按
 * conversation_id 查回、能按用户列出、能数消息、软删后行仍在（§6.1 不真删数据）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.locations=classpath:db/mysql/migration"
})
class ConversationRepositoryTest {

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationLogRepository conversationLogRepository;
    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    void testSaveAndFindByConversationId_returnsConversationWithOrderedMessages() {
        Long uid = seedUser("repo-user-1");
        conversationRepository.save(buildConversation(uid, "c-repo-1"));
        messageRepository.save(buildMessage("c-repo-1", uid, MessageRole.USER, "你好", 1));
        messageRepository.save(buildMessage("c-repo-1", uid, MessageRole.ASSISTANT, "你好呀", 2));

        Optional<Conversation> found = conversationRepository.findByConversationIdAndUserId("c-repo-1", uid);
        assertTrue(found.isPresent());
        assertEquals("c-repo-1", found.get().getConversationId());

        List<Message> msgs = messageRepository.findByConversationIdOrderBySeqAsc("c-repo-1");
        assertEquals(2, msgs.size());
        assertEquals(MessageRole.USER, msgs.get(0).getRole());
        assertEquals(MessageRole.ASSISTANT, msgs.get(1).getRole());
    }

    @Test
    void testFindByUserIdOrderByLastActiveAtDesc_returnsOwnConversationsSorted() {
        Long uid = seedUser("repo-user-2");
        Conversation older = buildConversation(uid, "c-old");
        older.setLastActiveAt(Instant.parse("2026-01-01T00:00:00Z"));
        Conversation newer = buildConversation(uid, "c-new");
        newer.setLastActiveAt(Instant.parse("2026-06-01T00:00:00Z"));
        conversationRepository.saveAll(List.of(older, newer));

        List<Conversation> list = conversationRepository.findByUserIdOrderByLastActiveAtDesc(uid);
        assertEquals(2, list.size());
        assertEquals("c-new", list.get(0).getConversationId());
    }

    @Test
    void testCountByConversationId_returnsMessageCount() {
        Long uid = seedUser("repo-user-3");
        conversationRepository.save(buildConversation(uid, "c-cnt"));
        messageRepository.save(buildMessage("c-cnt", uid, MessageRole.USER, "a", 1));
        messageRepository.save(buildMessage("c-cnt", uid, MessageRole.ASSISTANT, "b", 2));
        assertEquals(2, messageRepository.countByConversationId("c-cnt"));
    }

    @Test
    void testDeleteConversation_softDeletesRow_keepsRowInTable() {
        Long uid = seedUser("repo-user-4");
        Conversation saved = conversationRepository.save(buildConversation(uid, "c-del"));
        Long id = saved.getId();

        conversationRepository.delete(saved);

        assertTrue(conversationRepository.findByConversationIdAndUserId("c-del", uid).isEmpty(),
                "@SQLRestriction 应隐藏已软删会话");
        Object cnt = testEntityManager.getEntityManager()
                .createNativeQuery("select count(*) from conversation where id = ?")
                .setParameter(1, id)
                .getSingleResult();
        assertEquals(1, Integer.parseInt(cnt.toString()), "delete 应转为 UPDATE，行仍在表中（§6.1 不真删数据）");
    }

    private Long seedUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("ENC(x)");
        u.setRole(UserRole.USER);
        return testEntityManager.persistAndFlush(u).getId();
    }

    private Conversation buildConversation(Long userId, String conversationId) {
        Conversation c = new Conversation();
        c.setConversationId(conversationId);
        c.setUserId(userId);
        c.setTitle("t-" + conversationId);
        c.setMessageCount(0L);
        c.setStatus(ConversationStatus.ACTIVE);
        c.setLastActiveAt(Instant.now());
        return c;
    }

    private Message buildMessage(String conversationId, Long userId, MessageRole role, String content, int seq) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setUserId(userId);
        m.setRole(role);
        m.setContent(content);
        m.setSeq(seq);
        m.setStatus(MessageStatus.SENT);
        return m;
    }

    @Test
    void testSaveConversationLog_returnsLogWithTokens() {
        Long uid = seedUser("repo-user-5");
        ConversationLog log = new ConversationLog();
        log.setUserId(uid);
        log.setAgentId("agent-1");
        log.setConversationId("c-log-1");
        log.setQuestion("M6 日志落库测试");
        log.setInTok(12);
        log.setOutTok(34);
        log.setProvider("openai");
        log.setModel("gpt-4o");
        log.setFallback(false);
        conversationLogRepository.save(log);

        assertEquals(1, conversationLogRepository.findByUserIdOrderByCreatedAtDesc(uid,
                org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void testDeleteConversationLog_softDeletesRow_keepsRowInTable() {
        Long uid = seedUser("repo-user-6");
        ConversationLog log = new ConversationLog();
        log.setUserId(uid);
        log.setQuestion("软删日志测试");
        ConversationLog saved = conversationLogRepository.save(log);
        Long id = saved.getId();

        conversationLogRepository.delete(saved);

        assertTrue(conversationLogRepository.findByUserIdOrderByCreatedAtDesc(uid,
                org.springframework.data.domain.PageRequest.of(0, 10)).isEmpty(),
                "@SQLRestriction 应隐藏已软删日志");
        Object cnt = testEntityManager.getEntityManager()
                .createNativeQuery("select count(*) from conversation_log where id = ?")
                .setParameter(1, id)
                .getSingleResult();
        assertEquals(1, Integer.parseInt(cnt.toString()), "delete 应转为 UPDATE，行仍在表中（§6.1 不真删数据）");
    }

    @Test
    void testDeleteMessage_softDeletesRow_keepsRowAndSetsDeleted() {
        Long uid = seedUser("repo-msg-del");
        Message m = buildMessage("c-msg-del", uid, MessageRole.USER, "分区表软删", 1);
        Message saved = messageRepository.save(m);
        Long id = saved.getId();

        messageRepository.delete(saved);

        assertTrue(messageRepository.findByConversationIdOrderBySeqAsc("c-msg-del").isEmpty(),
                "@SQLRestriction 应隐藏已软删消息");
        Object deleted = testEntityManager.getEntityManager()
                .createNativeQuery("select CAST(deleted AS SIGNED) from message where id = ?")
                .setParameter(1, id)
                .getSingleResult();
        assertEquals(1, Integer.parseInt(deleted.toString()),
                "分区表 message 的 @SQLDelete 应转 UPDATE 并置 deleted=1（§6.1 不真删、§6.3 物理复合主键仍生效）");
    }
}

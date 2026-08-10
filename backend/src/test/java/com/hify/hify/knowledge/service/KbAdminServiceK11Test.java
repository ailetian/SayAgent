package com.hify.hify.knowledge.service;

import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.knowledge.repository.AgentKbLinkRepository;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.IndexingJobRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.web.KnowledgeBaseUpdateRequest;
import com.hify.hify.knowledge.web.KnowledgeBaseVO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KbAdminService K11 用例（收口 K8 缺口②：库更新 / 库删除级联）。纯 Mockito，不连真库 §7.10。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbAdminServiceK11Test {

    @Mock KnowledgeBaseRepository kbRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentChunkRepository documentChunkRepository;
    @Mock RetrievalLogRepository retrievalLogRepository;
    @Mock AgentService agentService;
    @Mock AgentKbLinkRepository agentKbLinkRepository;
    @Mock IndexingJobRepository indexingJobRepository;

    private KbAdminService kbAdminService;

    @BeforeEach
    void setUp() {
        loginAs("tester");
        kbAdminService = new KbAdminService(kbRepository, documentRepository, documentChunkRepository,
                retrievalLogRepository, new KbAccessGuard(kbRepository),
                agentService, agentKbLinkRepository, indexingJobRepository);
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void loginAs(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    private KnowledgeBase ownedKb(Long id, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCreatorId("tester");
        kb.setName(name);
        kb.setStatus(KnowledgeBase.Status.ACTIVE);
        return kb;
    }

    @Test
    void updateBase_partialUpdate_onlyOverwritesNonNullFields() {
        KnowledgeBase kb = ownedKb(1L, "old");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        KnowledgeBaseVO vo = kbAdminService.updateBase(1L,
                new KnowledgeBaseUpdateRequest("new", null, null, null, null, null, null));

        assertEquals("new", vo.name(), "应改为新名字");
        assertEquals("new", kb.getName(), "实体应同步被改");
        verify(kbRepository).save(kb);
    }

    @Test
    void deleteBase_cascades_softDeletesDocs_andClearsPgChunks() {
        KnowledgeBase kb = ownedKb(1L, "kb");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));

        Document d1 = new Document();
        d1.setDocumentId("doc-1");
        d1.setKbId(1L);
        Document d2 = new Document();
        d2.setDocumentId("doc-2");
        d2.setKbId(1L);
        when(documentRepository.findByKbId(1L)).thenReturn(List.of(d1, d2));

        kbAdminService.deleteBase(1L);

        // PG 切片按文档逐一清空（防孤儿召回）
        verify(documentChunkRepository, times(2)).deleteByDocumentId(any());
        verify(documentChunkRepository).deleteByDocumentId("doc-1");
        verify(documentChunkRepository).deleteByDocumentId("doc-2");
        // MySQL 文档行软删
        verify(documentRepository, times(2)).delete(any());
        // 库本身软删
        verify(kbRepository).delete(kb);
    }

    @Test
    void deleteBase_noDocuments_onlyDeletesBase() {
        KnowledgeBase kb = ownedKb(1L, "kb");
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        when(documentRepository.findByKbId(1L)).thenReturn(List.of());

        kbAdminService.deleteBase(1L);

        verify(documentChunkRepository, never()).deleteByDocumentId(any());
        verify(documentRepository, never()).delete(any());
        verify(kbRepository).delete(kb);
    }
}

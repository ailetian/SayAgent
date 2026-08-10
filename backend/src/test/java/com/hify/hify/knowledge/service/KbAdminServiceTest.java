package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.entity.Document;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.entity.RetrievalLog;
import com.hify.hify.knowledge.repository.DocumentRepository;
import com.hify.hify.knowledge.repository.DocumentChunkRepository;
import com.hify.hify.agent.service.AgentService;
import com.hify.hify.knowledge.repository.AgentKbLinkRepository;
import com.hify.hify.knowledge.repository.IndexingJobRepository;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.repository.RetrievalLogRepository;
import com.hify.hify.knowledge.web.HealthVO;
import com.hify.hify.knowledge.web.KnowledgeBaseCreateRequest;
import com.hify.hify.knowledge.web.KnowledgeBaseVO;
import com.hify.hify.knowledge.web.PageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * KbAdminService（建库 / 列表 / 体检）单测（K8）。
 *
 * <p>全 mock repository，不连真库（§7.10 规则35）。重点验：建库绑定当前用户 + 秘钥隔离字段、
 * keyset 分页的 hasMore/nextCursor 边界、体检三项指标在空库 / 有失败文档 / 有日志下的取值。
 */
@ExtendWith(MockitoExtension.class)
class KbAdminServiceTest {

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
    }

    private void loginAs(String username, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    private KnowledgeBase kb(long id, String creator) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName("kb-" + id);
        kb.setCreatorId(creator);
        kb.setStatus(KnowledgeBase.Status.ACTIVE);
        return kb;
    }

    // ===================== 建库 =====================

    @Test
    void createBase_bindsCurrentUserAsCreator_andReturnsActiveVo() {
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> {
            KnowledgeBase saved = inv.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        KnowledgeBaseVO vo = kbAdminService.createBase(new KnowledgeBaseCreateRequest(
                "研发知识库", "内部文档", null, null, null, null, null));

        assertEquals(9L, vo.id());
        assertEquals("研发知识库", vo.name());
        assertEquals("tester", vo.creatorId());
        assertEquals(KnowledgeBase.Status.ACTIVE, vo.status());
    }

    @Test
    void createBase_nullDescription_normalizedToEmptyString() {
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeBaseVO vo = kbAdminService.createBase(new KnowledgeBaseCreateRequest(
                "kb", null, null, null, null, null, null));

        assertEquals("", vo.description());
    }

    @Test
    void createBase_similarityThreshold_scaledToThreeDecimals() {
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeBaseVO vo = kbAdminService.createBase(new KnowledgeBaseCreateRequest(
                "kb", "", null, new BigDecimal("0.65432"), null, null, null));

        assertEquals(new BigDecimal("0.654"), vo.similarityThreshold());
    }

    // ===================== keyset 分页 =====================

    @Test
    void listBases_firstPageFull_setsHasMoreAndNextCursor() {
        List<KnowledgeBase> three = List.of(kb(3, "tester"), kb(2, "tester"), kb(1, "tester"));
        when(kbRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(three));

        PageVO<KnowledgeBaseVO> page = kbAdminService.listBases(null, 2);

        assertTrue(page.hasMore());
        assertEquals(2, page.items().size());
        assertEquals("2", page.nextCursor());
    }

    @Test
    void listBases_lastPage_noCursor() {
        when(kbRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(kb(3, "tester"))));

        PageVO<KnowledgeBaseVO> page = kbAdminService.listBases(null, 20);

        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        assertEquals(1, page.items().size());
    }

    @Test
    void listBases_withLastId_usesKeysetQueryNotOffset() {
        when(kbRepository.findByIdLessThanOrderByIdDesc(eq(5L), any(Pageable.class)))
                .thenReturn(List.of(kb(4, "tester")));

        PageVO<KnowledgeBaseVO> page = kbAdminService.listBases(5L, 20);

        assertEquals(1, page.items().size());
        assertEquals(4L, page.items().get(0).id());
        assertFalse(page.hasMore());
    }

    @Test
    void listBases_limitClampedToHundred() {
        List<KnowledgeBase> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(kb(200 - i, "tester"));
        }
        when(kbRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(many));

        PageVO<KnowledgeBaseVO> page = kbAdminService.listBases(null, 9999);

        assertEquals(100, page.items().size());
        assertTrue(page.hasMore());
    }

    // ===================== 体检 =====================

    @Test
    void health_emptyBase_reportsEmptyWithZeroScore() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb(1, "tester")));
        when(documentRepository.countByKbId(1L)).thenReturn(0L);
        when(documentRepository.countByKbIdAndStatus(eq(1L), any())).thenReturn(0L);
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L)).thenReturn(List.of());

        HealthVO h = kbAdminService.health(1L);

        assertEquals("EMPTY", h.basicHealth());
        assertEquals(0.0, h.healthScore());
        assertEquals(0, h.retrievalCount());
    }

    @Test
    void health_hasFailedDocs_reportsDegraded() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb(1, "tester")));
        when(documentRepository.countByKbId(1L)).thenReturn(4L);
        when(documentRepository.countByKbIdAndStatus(1L, Document.DocumentStatus.INDEXED)).thenReturn(3L);
        when(documentRepository.countByKbIdAndStatus(1L, Document.DocumentStatus.FAILED)).thenReturn(1L);
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L)).thenReturn(List.of());

        HealthVO h = kbAdminService.health(1L);

        assertEquals("DEGRADED", h.basicHealth());
        assertEquals(0.75, h.healthScore());
        assertEquals(1, h.docFailed());
    }

    @Test
    void health_averagesRetrievalLogs_forQualitySpeedAndRefusalRate() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb(1, "tester")));
        when(documentRepository.countByKbId(1L)).thenReturn(2L);
        when(documentRepository.countByKbIdAndStatus(1L, Document.DocumentStatus.INDEXED)).thenReturn(2L);
        when(documentRepository.countByKbIdAndStatus(1L, Document.DocumentStatus.FAILED)).thenReturn(0L);
        when(retrievalLogRepository.findTop50ByKbIdOrderByIdDesc(1L))
                .thenReturn(List.of(log("0.90", 100, false), log("0.70", 300, true)));

        HealthVO h = kbAdminService.health(1L);

        assertEquals("HEALTHY", h.basicHealth());
        assertEquals(0.8, h.hitQuality(), 1e-9);
        assertEquals(200.0, h.responseSpeedMs(), 1e-9);
        assertEquals(0.5, h.refusalRate(), 1e-9);
        assertEquals(2, h.retrievalCount());
    }

    @Test
    void health_kbNotFound_throwsKnowledgeBaseNotFound() {
        when(kbRepository.findById(anyLong())).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> kbAdminService.health(404L));

        assertEquals(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void health_notCreatorNorAdmin_throwsForbidden() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb(1, "someone-else")));

        BizException ex = assertThrows(BizException.class, () -> kbAdminService.health(1L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    private RetrievalLog log(String topScore, long costMs, boolean rejected) {
        RetrievalLog r = new RetrievalLog();
        r.setTopScore(new BigDecimal(topScore));
        r.setCostMs(costMs);
        r.setRejected(rejected);
        return r;
    }
}

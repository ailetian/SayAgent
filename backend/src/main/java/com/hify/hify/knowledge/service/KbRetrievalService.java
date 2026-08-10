package com.hify.hify.knowledge.service;

import com.hify.hify.knowledge.config.RagConfig;
import com.hify.hify.knowledge.config.RagProperties;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import com.hify.hify.knowledge.retriever.RetrievalPort;
import com.hify.hify.knowledge.retriever.RetrievalResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库检索唯一入口（K4）。
 *
 * <p>大白话：全项目「对一个/多个知识库跑混合检索」只有这一处实现。probe（试问台）、
 * 聊天（ConversationService）、正式问答与题集打分（RagQueryService.ask/eval）全部经本类，
 * 严禁在各自调用点再 copy 一遍 {@code retrieveHybrid} + 生效阈值计算（双份实现必然分裂，
 * 曾经就因此出现「试问台能查到、聊天查不到」的 bug）。
 *
 * <p>职责：① 库级生效配置怎么算（库没配就吃 application.yml 全局默认，K2）；② 调哪个检索方法
 * （统一 retrieveHybrid 混合检索）；③ 阈值从哪来（库级 rag_config，已由 KbAdminService 同步 UI 设置）。
 * 改检索行为只改这一处，所有上游自动同步。
 */
@Service
public class KbRetrievalService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RetrievalPort retrievalPort;
    private final RagProperties ragProperties;

    public KbRetrievalService(KnowledgeBaseRepository knowledgeBaseRepository,
                              RetrievalPort retrievalPort,
                              RagProperties ragProperties) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.retrievalPort = retrievalPort;
        this.ragProperties = ragProperties;
    }

    /** 库级生效配置：库级覆盖全局兜底（K2）。probe / chat / ask / eval 共用同一份实现，禁止各自再算。 */
    public RagConfig effectiveConfig(KnowledgeBase kb) {
        return kb.getEffectiveConfig(RagConfig.fromGlobal(ragProperties));
    }

    /** 单库混合检索（probe / chat 用）：阈值来自该库生效配置。库不存在抛 {@link IllegalArgumentException}。 */
    public List<RetrievalResult> retrieve(Long kbId, String query) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: " + kbId));
        return retrievalPort.retrieveHybrid(query, List.of(kbId), effectiveConfig(kb));
    }

    /** 多库联合混合检索（RagQueryService 的 ask / eval 用，支持 mountedKbIds 多个库）。 */
    public List<RetrievalResult> retrieve(List<Long> kbIds, String query, RagConfig ragConfig) {
        return retrievalPort.retrieveHybrid(query, kbIds, ragConfig);
    }
}

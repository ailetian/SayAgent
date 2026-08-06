package com.hify.hify.knowledge.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.knowledge.entity.KnowledgeBase;
import com.hify.hify.knowledge.repository.KnowledgeBaseRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 知识库「管理权」判权闸门（K7 权限模型的单一事实源，K8 拆分抽出）。
 *
 * <p>大白话：判断「当前登录的人能不能动这个知识库」只有一处实现，谁要用谁注入，
 * 避免上传服务、管理服务、问答服务各写一份判权、日后改规则漏改。
 *
 * <p>权限模型（K7 退役 kb_access 授权表后）：
 * <ul>
 *   <li><b>管理权</b> = 知识库创建者（{@code KnowledgeBase.creatorId}）或系统管理员（ROLE_ADMIN）；</li>
 *   <li><b>查询权</b> = 委托 Agent 挂载关系（见 {@link MountService}），不在本类范围。</li>
 * </ul>
 */
@Component
public class KbAccessGuard {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KbAccessGuard(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 取知识库并同时判权：库不存在抛 {@code KNOWLEDGE_BASE_NOT_FOUND}，无权抛 {@code FORBIDDEN}。
     *
     * <p>大白话：把「先查库、再看你有没有权限」这两步合成一步，调用方少写两行还不会漏判。
     */
    public KnowledgeBase requireAccessible(Long kbId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        assertAccess(kb);
        return kb;
    }

    /** 判管理权：管理员直接放行，否则必须是该库创建者。 */
    public void assertAccess(KnowledgeBase kb) {
        if (isAdmin()) {
            return;
        }
        String creator = kb.getCreatorId();
        if (creator == null || !creator.equals(currentUser())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该知识库（仅创建者/管理员）");
        }
    }

    /** 首次上传时绑定创建者（管理权归属，K7 不再写 kb_access 授权表）。 */
    public void ensureCreator(KnowledgeBase kb) {
        if (kb.getCreatorId() == null) {
            kb.setCreatorId(currentUser());
            knowledgeBaseRepository.save(kb);
        }
    }

    /** 是否管理员（ROLE_ADMIN）。 */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** 取当前登录用户名（AuthFilter 将 username 写入 SecurityContext principal）。 */
    public String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return auth.getName();
    }
}

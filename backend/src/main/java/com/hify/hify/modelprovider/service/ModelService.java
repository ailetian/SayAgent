package com.hify.hify.modelprovider.service;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;
import com.hify.hify.modelprovider.dto.ModelProviderVO;
import com.hify.hify.modelprovider.dto.ProviderCreateRequest;
import com.hify.hify.modelprovider.dto.ProviderUpdateRequest;
import com.hify.hify.modelprovider.entity.ModelProvider;
import com.hify.hify.modelprovider.repository.ModelProviderRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型管理业务（§3.4 分层纪律：Controller 只调本服务，真正增删改查在这里）。
 *
 * <p>大白话：给运维/前台一套管模型的接口——列出、查看、新增、修改、删除、设默认。
 * <ul>
 *   <li>读（list/get）任何已登录用户可用；写（create/update/delete/setDefault）仅 ADMIN（§7.11 服务层再核）；</li>
 *   <li>对外一律返回 {@link ModelProviderVO}，秘钥 secret 被 {@code @JsonIgnore} 屏蔽（§7.11 规则37）；</li>
 *   <li>找不到记录抛 {@code MODEL_NOT_FOUND}；删默认模型等非法操作抛 {@code FORBIDDEN}（§7.3 规则10-14）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ModelService {

    private final ModelProviderRepository repository;

    /** 列出全部模型（软删除已由 @SQLRestriction 过滤）。 */
    public List<ModelProviderVO> listProviders() {
        return repository.findAll().stream()
                .map(ModelProviderVO::from)
                .toList();
    }

    /** 查看单个模型；不存在抛 MODEL_NOT_FOUND。 */
    public ModelProviderVO getProvider(Long id) {
        ModelProvider p = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_NOT_FOUND, "id=" + id));
        return ModelProviderVO.from(p);
    }

    /**
     * 跨模块校验：指定模型厂商是否存在（M4/T2 硬指标，供 Agent 等外部模块调用）。
     *
     * <p>大白话：Agent 配置里填了「默认模型厂商 id」，但 Agent 模块不该直接碰 modelprovider 的
     * 实体/仓储（§3.3 解耦），于是只暴露这一个轻量校验口——存在则静默返回，不存在抛
     * {@code MODEL_NOT_FOUND}。AgentService 据此确认「厂商真实存在」后再落库。
     */
    public void checkProviderIdExists(Long id) {
        if (!repository.existsById(id)) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND, "modelProviderId=" + id);
        }
    }

    /** 新增模型（仅 ADMIN）。 */
    public ModelProviderVO createProvider(ProviderCreateRequest req) {
        assertAdmin();
        ModelProvider p = new ModelProvider();
        p.setName(req.name());
        p.setApiUrl(req.apiUrl());
        p.setSecret(req.secret());                 // 秘钥来自请求体，绝不打印（§7.11）
        p.setProviderType(req.providerType());
        p.setModel(req.model());
        p.setEnabled(req.enabled() != null ? req.enabled() : true);
        p.setDefaultModel(req.defaultModel() != null ? req.defaultModel() : false);
        p.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return ModelProviderVO.from(repository.save(p));
    }

    /** 修改模型（仅 ADMIN）；仅更新请求中非 null 的字段。 */
    public ModelProviderVO updateProvider(Long id, ProviderUpdateRequest req) {
        assertAdmin();
        ModelProvider p = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_NOT_FOUND, "id=" + id));
        if (req.name() != null) {
            p.setName(req.name());
        }
        if (req.apiUrl() != null) {
            p.setApiUrl(req.apiUrl());
        }
        if (req.secret() != null) {
            p.setSecret(req.secret());             // 不打印（§7.11）
        }
        if (req.providerType() != null) {
            p.setProviderType(req.providerType());
        }
        if (req.model() != null) {
            p.setModel(req.model());
        }
        if (req.enabled() != null) {
            p.setEnabled(req.enabled());
        }
        if (req.defaultModel() != null) {
            p.setDefaultModel(req.defaultModel());
        }
        if (req.sortOrder() != null) {
            p.setSortOrder(req.sortOrder());
        }
        return ModelProviderVO.from(repository.save(p));
    }

    /** 删除模型（仅 ADMIN）；默认模型禁止删除（避免路由无主）。 */
    public void deleteProvider(Long id) {
        assertAdmin();
        ModelProvider p = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_NOT_FOUND, "id=" + id));
        if (Boolean.TRUE.equals(p.getDefaultModel())) {
            throw new BizException(ErrorCode.FORBIDDEN, "默认模型不可删除");
        }
        repository.delete(p);                      // 软删除（@SQLDelete）
    }

    /** 设默认模型（仅 ADMIN）；先把旧的默认取消，再把目标置为默认。 */
    public ModelProviderVO setDefault(Long id) {
        assertAdmin();
        ModelProvider target = repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_NOT_FOUND, "id=" + id));
        repository.findByDefaultModelTrue().ifPresent(old -> {
            if (!old.getId().equals(target.getId())) {
                old.setDefaultModel(false);
                repository.save(old);
            }
        });
        target.setDefaultModel(true);
        return ModelProviderVO.from(repository.save(target));
    }

    /** 服务层权限再核（§7.11）：当前登录用户须为 ROLE_ADMIN，否则 FORBIDDEN。 */
    private void assertAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅 ADMIN 可增删改模型");
        }
    }
}

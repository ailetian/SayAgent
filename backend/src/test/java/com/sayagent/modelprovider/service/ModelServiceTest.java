package com.sayagent.modelprovider.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayagent.common.exception.BizException;
import com.sayagent.common.exception.ErrorCode;
import com.sayagent.modelprovider.domain.enums.ProviderType;
import com.sayagent.modelprovider.dto.ModelProviderVO;
import com.sayagent.modelprovider.dto.ProviderCreateRequest;
import com.sayagent.modelprovider.dto.ProviderUpdateRequest;
import com.sayagent.modelprovider.entity.ModelProvider;
import com.sayagent.modelprovider.repository.ModelProviderRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModelService 单测（mock 仓库，不连真实库，§7.10 规则35）。
 * 聚焦：CRUD 链路、ADMIN 服务层校验、秘钥脱敏序列化。
 */
@ExtendWith(MockitoExtension.class)
class ModelServiceTest {

    @Mock
    private ModelProviderRepository repository;

    @InjectMocks
    private ModelService modelService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        var auth = new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private ModelProvider sample(Long id, ProviderType type, boolean isDefault) {
        ModelProvider p = new ModelProvider();
        p.setId(id);
        p.setName("p" + id);
        p.setApiUrl("https://api.example.com/" + id);
        p.setSecret("secret-" + id);
        p.setProviderType(type);
        p.setModel("model-" + id);
        p.setEnabled(true);
        p.setDefaultModel(isDefault);
        p.setSortOrder(0);
        return p;
    }

    @Test
    @DisplayName("listProviders：仓库返回两条，转成两个 VO")
    void testListProviders_repositoryReturnsTwo_returnsTwoVOs() {
        when(repository.findAll()).thenReturn(List.of(sample(1L, ProviderType.OPENAI, false),
                sample(2L, ProviderType.CLAUDE, false)));

        List<ModelProviderVO> vos = modelService.listProviders();

        assertEquals(2, vos.size());
        assertEquals("p1", vos.get(0).name());
        verify(repository).findAll();
    }

    @Test
    @DisplayName("getProvider：存在 id，返回对应 VO")
    void testGetProvider_existingId_returnsVO() {
        when(repository.findById(1L)).thenReturn(Optional.of(sample(1L, ProviderType.OPENAI, false)));

        ModelProviderVO vo = modelService.getProvider(1L);

        assertEquals(1L, vo.id());
        assertEquals(ProviderType.OPENAI, vo.providerType());
    }

    @Test
    @DisplayName("getProvider：id 不存在，抛 MODEL_NOT_FOUND")
    void testGetProvider_missingId_throwsModelNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> modelService.getProvider(99L));
        assertEquals(ErrorCode.MODEL_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("createProvider：ADMIN 角色，落库并返回 VO")
    void testCreateProvider_adminRole_savesAndReturnsVO() {
        loginAs("ADMIN");
        ProviderCreateRequest req = new ProviderCreateRequest("gpt", "https://api.openai.com/v1",
                "sk-123", ProviderType.OPENAI, "gpt-4o", true, false, 0);
        when(repository.save(any(ModelProvider.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelProviderVO vo = modelService.createProvider(req);

        assertEquals("gpt", vo.name());
        assertEquals(ProviderType.OPENAI, vo.providerType());
        verify(repository).save(any(ModelProvider.class));
    }

    @Test
    @DisplayName("createProvider：非 ADMIN，抛 FORBIDDEN")
    void testCreateProvider_nonAdminRole_throwsForbidden() {
        loginAs("USER");
        ProviderCreateRequest req = new ProviderCreateRequest("gpt", "https://api.openai.com/v1",
                "sk-123", ProviderType.OPENAI, "gpt-4o", true, false, 0);

        BizException ex = assertThrows(BizException.class, () -> modelService.createProvider(req));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, times(0)).save(any());
    }

    @Test
    @DisplayName("updateProvider：存在 id，更新非空字段并返回 VO")
    void testUpdateProvider_existingId_updatesFieldsAndReturnsVO() {
        loginAs("ADMIN");
        ModelProvider existing = sample(1L, ProviderType.OPENAI, false);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ModelProvider.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderUpdateRequest req = new ProviderUpdateRequest("gpt-new", null, null,
                null, null, false, null, null);

        ModelProviderVO vo = modelService.updateProvider(1L, req);

        assertEquals("gpt-new", vo.name());
        assertEquals(false, vo.enabled());
        assertEquals("https://api.example.com/1", vo.apiUrl());   // 未传保持原值
    }

    @Test
    @DisplayName("deleteProvider：默认模型，抛 FORBIDDEN 不删除")
    void testDeleteProvider_defaultModel_throwsForbidden() {
        loginAs("ADMIN");
        when(repository.findById(1L)).thenReturn(Optional.of(sample(1L, ProviderType.OPENAI, true)));

        BizException ex = assertThrows(BizException.class, () -> modelService.deleteProvider(1L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(repository, times(0)).delete(any());
    }

    @Test
    @DisplayName("deleteProvider：非默认且 ADMIN，执行软删除")
    void testDeleteProvider_nonDefault_adminRole_softDeletes() {
        loginAs("ADMIN");
        ModelProvider p = sample(1L, ProviderType.OPENAI, false);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        modelService.deleteProvider(1L);

        verify(repository).delete(p);
    }

    @Test
    @DisplayName("setDefault：存在 id，取消旧默认并置新默认")
    void testSetDefault_existingId_clearsOldAndSetsNew() {
        loginAs("ADMIN");
        ModelProvider oldDefault = sample(1L, ProviderType.OPENAI, true);
        ModelProvider target = sample(2L, ProviderType.CLAUDE, false);
        when(repository.findByDefaultModelTrue()).thenReturn(Optional.of(oldDefault));
        when(repository.findById(2L)).thenReturn(Optional.of(target));
        when(repository.save(any(ModelProvider.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelProviderVO vo = modelService.setDefault(2L);

        assertFalse(oldDefault.getDefaultModel());
        assertEquals(true, vo.defaultModel());
        verify(repository, times(2)).save(any(ModelProvider.class));
    }

    @Test
    @DisplayName("序列化：VO 含 secret，JSON 输出不含 secret 明文（§7.11 脱敏）")
    void testSerialization_voWithSecret_jsonExcludesSecret() throws Exception {
        ModelProvider p = sample(1L, ProviderType.OPENAI, false);
        ModelProviderVO vo = ModelProviderVO.from(p);

        String json = objectMapper.writeValueAsString(vo);

        assertFalse(json.contains("secret"), "响应 JSON 不应含 secret 字段");
        assertFalse(json.contains("secret-1"), "响应 JSON 不应含秘钥明文值");
        assertFalse(json.contains("apiKey"), "响应 JSON 不应含 apiKey 字段");
    }
}

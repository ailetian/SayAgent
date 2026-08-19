package com.sayagent.modelprovider.web;

import com.sayagent.common.Result;
import com.sayagent.modelprovider.dto.ModelProviderVO;
import com.sayagent.modelprovider.dto.ProviderCreateRequest;
import com.sayagent.modelprovider.dto.ProviderUpdateRequest;
import com.sayagent.modelprovider.service.ModelService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型管理接口「前台柜员」（§3.4 分层纪律：Controller 极薄——只收请求、调 service、装统一盒子）。
 *
 * <p>大白话：这是 {@code /api/models} 一组入口。自己不写业务逻辑：
 * <ul>
 *   <li>鉴权第一道闸由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 兜底——未登录直接 401；</li>
 *   <li>增删改的 ADMIN 权限在服务层 {@link ModelService} 再核（§7.11）；</li>
 *   <li>所有响应统一包 {@link Result}；失败由 {@code GlobalExceptionHandler} 翻译。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @GetMapping
    public Result<List<ModelProviderVO>> listProviders() {
        return Result.ok(modelService.listProviders());
    }

    @GetMapping("/{id}")
    public Result<ModelProviderVO> getProvider(@PathVariable Long id) {
        return Result.ok(modelService.getProvider(id));
    }

    @PostMapping
    public Result<ModelProviderVO> createProvider(@Valid @RequestBody ProviderCreateRequest request) {
        return Result.ok(modelService.createProvider(request));
    }

    @PutMapping("/{id}")
    public Result<ModelProviderVO> updateProvider(@PathVariable Long id,
                                                  @RequestBody ProviderUpdateRequest request) {
        return Result.ok(modelService.updateProvider(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteProvider(@PathVariable Long id) {
        modelService.deleteProvider(id);
        return Result.ok();
    }

    @PostMapping("/{id}/default")
    public Result<ModelProviderVO> setDefault(@PathVariable Long id) {
        return Result.ok(modelService.setDefault(id));
    }
}

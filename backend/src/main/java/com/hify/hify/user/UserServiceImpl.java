package com.hify.hify.user;

import com.hify.hify.common.exception.BizException;
import com.hify.hify.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/**
 * UserService 默认实现：走 user 模块内部的 UserRepository（内部类不外泄）。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public Long resolveUserId(String username) {
        return userRepository.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED,
                        "用户不存在或已软删: " + username))
                .getId();
    }
}

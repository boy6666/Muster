package com.muster.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.muster.auth.dto.LoginResponse;
import com.muster.common.ApiException;
import com.muster.common.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AdminUserMapper adminUserMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AdminUserMapper adminUserMapper, JwtService jwtService, PasswordEncoder passwordEncoder) {
        this.adminUserMapper = adminUserMapper;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse login(String username, String password) {
        AdminUser user = findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
        }
        return new LoginResponse(jwtService.issue(user.getId(), user.getUsername()), user.getUsername());
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new ApiException(ErrorCode.VALIDATION, "新密码至少 6 位");
        }
        AdminUser user = findByUsername(username);
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION, "原密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        adminUserMapper.updateById(user);
    }

    private AdminUser findByUsername(String username) {
        return adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username));
    }
}

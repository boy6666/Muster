package com.muster.auth;

import com.muster.auth.dto.ChangePasswordRequest;
import com.muster.auth.dto.LoginRequest;
import com.muster.auth.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }

    @PutMapping("/password")
    public Map<String, Object> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                              Authentication authentication) {
        authService.changePassword(authentication.getName(), request.oldPassword(), request.newPassword());
        return Map.of("ok", true);
    }
}

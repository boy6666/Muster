package com.muster.config;

import com.muster.auth.AdminUser;
import com.muster.auth.AdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminSeeder {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    @Bean
    public CommandLineRunner seedAdmin(AdminUserMapper mapper, PasswordEncoder encoder) {
        return args -> {
            if (mapper.selectCount(null) == 0) {
                AdminUser admin = new AdminUser();
                admin.setUsername("admin");
                admin.setPasswordHash(encoder.encode("admin123"));
                mapper.insert(admin);
                log.warn("已创建默认管理员 admin/admin123，请登录后立即在「修改密码」中更改");
            }
        };
    }
}

package com.muster.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/** 管理操作审计日志：记录操作者、动作与简要详情。 */
@Service
public class OpLogService {

    private final OpLogMapper opLogMapper;
    private final Clock clock;

    public OpLogService(OpLogMapper opLogMapper, Clock clock) {
        this.opLogMapper = opLogMapper;
        this.clock = clock;
    }

    public void record(String action, String detail) {
        OpLog log = new OpLog();
        log.setAdminUsername(currentUsername());
        log.setAction(action);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now(clock));
        opLogMapper.insert(log);
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}

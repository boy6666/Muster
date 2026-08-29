package com.muster.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.muster.audit.dto.OpLogView;
import com.muster.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final OpLogMapper opLogMapper;

    public AuditController(OpLogMapper opLogMapper) {
        this.opLogMapper = opLogMapper;
    }

    @GetMapping("/logs")
    public PageResult<OpLogView> logs(@RequestParam(required = false) String action,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        LambdaQueryWrapper<OpLog> wrapper = new LambdaQueryWrapper<OpLog>()
                .orderByDesc(OpLog::getId);
        if (action != null && !action.isBlank()) {
            wrapper.eq(OpLog::getAction, action.trim().toUpperCase());
        }
        Page<OpLog> result = opLogMapper.selectPage(Page.of(page, size), wrapper);
        List<OpLogView> records = result.getRecords().stream()
                .map(l -> new OpLogView(l.getId(), l.getAdminUsername(), l.getAction(), l.getDetail(), l.getCreatedAt()))
                .toList();
        return new PageResult<>(result.getTotal(), records);
    }
}

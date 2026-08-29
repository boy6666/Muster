package com.muster.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("op_log")
public class OpLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String adminUsername;
    private String action;
    private String detail;
    private LocalDateTime createdAt;
}

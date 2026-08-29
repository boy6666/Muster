package com.muster.team;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("team")
public class Team {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private String name;

    private String status;

    private String rejectReason;

    private LocalDateTime submittedAt;

    private LocalDateTime updatedAt;
}

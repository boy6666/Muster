package com.muster.team;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("team_event")
public class TeamEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long activityId;
    private String type;
    private String detail;
    private LocalDateTime createdAt;
}

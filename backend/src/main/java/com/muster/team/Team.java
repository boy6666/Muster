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

    /** 组长的 person.id；DRAFT 建组即设置，管理员改组时保持指向组内成员。 */
    private Long leaderPersonId;

    private String rejectReason;

    /** 组级能力令牌：报名表单查看/改组时必须携带，防止共享二维码遍历 teamId。 */
    private String capToken;

    private LocalDateTime submittedAt;

    private LocalDateTime updatedAt;
}

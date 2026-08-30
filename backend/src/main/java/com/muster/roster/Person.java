package com.muster.roster;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("person")
public class Person {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private String employeeId;

    private String name;

    private String phone;

    private String department;

    private LocalDateTime createdAt;
}

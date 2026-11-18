package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 投递记录（BOSS / 招聘会企业 / 邮件） */
@Data
@TableName("delivery_record")
public class DeliveryRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** boss / fair / email / manual */
    private String channel;
    private String company;
    private String job;
    private String source;
    private Long resumeVersionId;
    private String status;
    private String result;
    private LocalDateTime createdAt;
}

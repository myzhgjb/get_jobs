package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自动流水线任务：招聘会企业 -> 定制简历 -> 投递邮件。
 * company_id 唯一，保证重复采集不会重复定制/重复发信。
 */
@Data
@TableName("auto_apply_task")
public class AutoApplyTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String companyName;
    private String jobTitle;
    private Long eventId;
    private Long resumeVersionId;
    private Long mailId;
    /** SKIPPED / RESUME_READY / MAIL_QUEUED / SENT / FAILED */
    private String status;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

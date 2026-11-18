package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 邮件发件箱：投递回执 / 汇总报告 */
@Data
@TableName("mail_outbox")
public class MailOutboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String toAddr;
    private String subject;
    private String body;
    private String bodyFormat;
    /** PENDING / SENT / FAILED */
    private String status;
    private String error;
    /** 附件绝对路径（如定制简历 docx），为空表示无附件 */
    private String attachmentPath;
    /** 附件在邮件里显示的文件名 */
    private String attachName;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
}

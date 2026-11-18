package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 按岗位定制的简历版本 */
@Data
@TableName("resume_version")
public class ResumeVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String targetJob;
    private String jdText;
    private String contentJson;
    private String docxPath;
    private String pdfPath;
    private LocalDateTime createdAt;
}

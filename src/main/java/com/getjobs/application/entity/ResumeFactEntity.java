package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 简历事实库：AI 只能基于这些事实改写，不得编造 */
@Data
@TableName("resume_fact")
public class ResumeFactEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 教育 / 技能 / 项目 / 实习 / 荣誉 / 其他 */
    private String category;
    private String title;
    private String content;
    /** 逗号分隔标签，用于匹配 */
    private String tags;
    private String startDate;
    private String endDate;
    private Integer verified;
}

package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 招聘会参会企业 */
@Data
@TableName("fair_company")
public class FairCompanyEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long eventId;
    private String companyName;
    private String industry;
    private String scale;
    /** JSON 数组：[{title, requirement, salary}] */
    private String jobsJson;
    private String applyUrl;
    private String applyEmail;
    private Integer matchScore;
    private String matchReason;
}

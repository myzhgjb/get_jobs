package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 招聘会数据源站点 */
@Data
@TableName("fair_site")
public class FairSiteEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String siteCode;
    private String name;
    private String baseUrl;
    /** 适配器 bean 名称：ncss / sample / generic */
    private String adapter;
    private Integer enabled;
    private String configJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

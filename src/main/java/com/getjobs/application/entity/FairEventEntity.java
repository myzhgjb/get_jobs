package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 招聘会 / 宣讲会 */
@Data
@TableName("fair_event")
public class FairEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String siteCode;
    private String eventKey;
    private String title;
    /** 双选会 / 宣讲会 / 线上 */
    private String type;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String location;
    private String city;
    private String host;
    private String detailUrl;
    private Integer companyCount;
    private String summary;
    private String rawJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

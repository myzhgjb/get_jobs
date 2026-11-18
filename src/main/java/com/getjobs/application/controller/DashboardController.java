package com.getjobs.application.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.FairCompanyEntity;
import com.getjobs.application.entity.FairEventEntity;
import com.getjobs.application.entity.MailOutboxEntity;
import com.getjobs.application.mapper.DeliveryRecordMapper;
import com.getjobs.application.mapper.FairCompanyMapper;
import com.getjobs.application.mapper.FairEventMapper;
import com.getjobs.application.mapper.MailOutboxMapper;
import com.getjobs.application.mapper.ResumeFactMapper;
import com.getjobs.application.mapper.ResumeVersionMapper;
import com.getjobs.application.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 首页总览统计 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DashboardController {

    private final FairEventMapper eventMapper;
    private final FairCompanyMapper companyMapper;
    private final ResumeFactMapper factMapper;
    private final ResumeVersionMapper versionMapper;
    private final DeliveryRecordMapper deliveryMapper;
    private final MailOutboxMapper mailMapper;
    private final MailService mailService;

    @GetMapping
    public Map<String, Object> stats() {
        String now = LocalDateTime.now().toString();
        String in14 = LocalDateTime.now().plusDays(14).toString();

        long upcoming = eventMapper.selectCount(new QueryWrapper<FairEventEntity>()
                .ge("start_time", now).le("start_time", in14));
        long events = eventMapper.selectCount(new QueryWrapper<>());
        long companies = companyMapper.selectCount(new QueryWrapper<>());
        long facts = factMapper.selectCount(new QueryWrapper<>());
        long versions = versionMapper.selectCount(new QueryWrapper<>());
        long deliveries = deliveryMapper.selectCount(new QueryWrapper<>());
        long pendingMails = mailMapper.selectCount(new QueryWrapper<MailOutboxEntity>().eq("status", "PENDING"));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("upcomingFairs", upcoming);
        r.put("totalFairs", events);
        r.put("companies", companies);
        r.put("resumeFacts", facts);
        r.put("resumeVersions", versions);
        r.put("deliveries", deliveries);
        r.put("pendingMails", pendingMails);
        r.put("mailTo", mailService.defaultRecipient());
        return r;
    }
}

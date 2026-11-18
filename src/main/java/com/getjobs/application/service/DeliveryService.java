package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.DeliveryRecordEntity;
import com.getjobs.application.entity.ResumeVersionEntity;
import com.getjobs.application.mapper.DeliveryRecordMapper;
import com.getjobs.application.mapper.ResumeVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投递记录：BOSS / 招聘会企业 / 邮件 / 手动。
 * 每次记录都会自动生成一封投递回执邮件进入发件箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryRecordMapper deliveryMapper;
    private final ResumeVersionMapper versionMapper;
    private final MailService mailService;

    public Map<String, Object> record(String channel, String company, String job, String source,
                                      Long resumeVersionId, String status, String result) {
        return record(channel, company, job, source, resumeVersionId, status, result, null, null);
    }

    /** 带附件的投递记录：回执邮件同时带上定制简历 */
    public Map<String, Object> record(String channel, String company, String job, String source,
                                      Long resumeVersionId, String status, String result,
                                      String attachmentPath, String attachName) {
        DeliveryRecordEntity d = new DeliveryRecordEntity();
        d.setChannel(channel == null ? "manual" : channel);
        d.setCompany(company);
        d.setJob(job);
        d.setSource(source);
        d.setResumeVersionId(resumeVersionId);
        d.setStatus(status == null ? "SUCCESS" : status);
        d.setResult(result);
        d.setCreatedAt(LocalDateTime.now());
        deliveryMapper.insert(d);

        String resumeName = null;
        if (resumeVersionId != null) {
            ResumeVersionEntity v = versionMapper.selectById(resumeVersionId);
            if (v != null) resumeName = v.getName() + "（" + v.getTargetJob() + "）";
        }
        mailService.queueDeliveryReceipt(d, resumeName, attachmentPath, attachName);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("id", d.getId());
        r.put("receiptQueued", true);
        r.put("receiptTo", mailService.defaultRecipient());
        return r;
    }

    public Map<String, Object> list(String channel, Integer limit) {
        QueryWrapper<DeliveryRecordEntity> qw = new QueryWrapper<>();
        if (channel != null && !channel.isBlank()) qw.eq("channel", channel);
        qw.orderByDesc("id").last("LIMIT " + (limit == null || limit <= 0 ? 200 : limit));
        List<DeliveryRecordEntity> list = deliveryMapper.selectList(qw);

        long success = list.stream().filter(x -> "SUCCESS".equalsIgnoreCase(x.getStatus())).count();
        long failed = list.stream().filter(x -> "FAILED".equalsIgnoreCase(x.getStatus())).count();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("total", list.size());
        r.put("successCount", success);
        r.put("failedCount", failed);
        r.put("records", list);
        return r;
    }
}

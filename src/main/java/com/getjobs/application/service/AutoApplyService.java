package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.AutoApplyTaskEntity;
import com.getjobs.application.entity.FairCompanyEntity;
import com.getjobs.application.entity.FairEventEntity;
import com.getjobs.application.mapper.AutoApplyTaskMapper;
import com.getjobs.application.mapper.FairCompanyMapper;
import com.getjobs.application.mapper.FairEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动流水线：采集招聘会 → 按匹配分挑企业 → 按 JD 定制简历 → 生成投递邮件 → SMTP 发出。
 * <p>
 * 幂等：一家企业只处理一次（auto_apply_task.company_id 唯一）。
 * 边界：只投「公开了投递邮箱」的企业；无邮箱的生成简历并标记待人工/现场投递，
 * 不做验证码绕过、不伪造身份、不向抓取到的私人联系方式群发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoApplyService {

    private final FairService fairService;
    private final FairCompanyMapper companyMapper;
    private final FairEventMapper eventMapper;
    private final ResumeService resumeService;
    private final DeliveryService deliveryService;
    private final MailService mailService;
    private final AutoApplyTaskMapper taskMapper;
    private final ConfigService configService;

    private volatile boolean running = false;

    /** 每天 08:30 自动采集 + 投递；可用 AUTO_PIPELINE_ENABLED=0 关闭 */
    @Scheduled(cron = "0 30 8 * * ?")
    public void dailyPipeline() {
        if ("0".equals(configService.getConfigValue("AUTO_PIPELINE_ENABLED"))) {
            log.info("自动流水线已关闭（AUTO_PIPELINE_ENABLED=0）");
            return;
        }
        log.info("自动流水线开始（定时任务）");
        try {
            Map<String, Object> r = run(true, 3, 50, false);
            log.info("自动流水线结束: {}", r.get("message"));
        } catch (Exception e) {
            log.error("自动流水线异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行一轮流水线。
     *
     * @param doCrawl  是否先采集
     * @param maxItems 本轮最多处理几家企业
     * @param minScore 匹配分门槛
     * @param dryRun   只列出候选，不生成简历/不发信
     */
    public Map<String, Object> run(boolean doCrawl, int maxItems, int minScore, boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (running) {
            result.put("success", false);
            result.put("message", "流水线正在运行中，请稍后再试");
            return result;
        }
        running = true;
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();
        try {
            if (doCrawl) {
                Map<String, Object> crawl = fairService.crawl("all");
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("step", "采集招聘会");
                s.put("fetched", crawl.get("fetched"));
                s.put("inserted", crawl.get("inserted"));
                s.put("updated", crawl.get("updated"));
                s.put("details", crawl.get("details"));
                steps.add(s);
            }

            List<FairCompanyEntity> candidates = companyMapper.selectList(
                    new QueryWrapper<FairCompanyEntity>()
                            .ge("match_score", minScore)
                            .orderByDesc("match_score")
                            .last("LIMIT " + Math.max(1, maxItems * 4)));

            // 过滤已处理过的企业
            List<FairCompanyEntity> fresh = new ArrayList<>();
            for (FairCompanyEntity c : candidates) {
                Long exists = taskMapper.selectCount(new QueryWrapper<AutoApplyTaskEntity>()
                        .eq("company_id", c.getId()));
                if (exists == null || exists == 0) fresh.add(c);
                if (fresh.size() >= maxItems) break;
            }

            Map<String, Object> s2 = new LinkedHashMap<>();
            s2.put("step", "筛选企业");
            s2.put("minScore", minScore);
            s2.put("candidates", fresh.size());
            steps.add(s2);

            if (dryRun) {
                List<Map<String, Object>> preview = new ArrayList<>();
                for (FairCompanyEntity c : fresh) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("company", c.getCompanyName());
                    p.put("score", c.getMatchScore());
                    p.put("job", firstJobTitle(c.getJobsJson()));
                    p.put("email", c.getApplyEmail());
                    p.put("canEmail", notBlank(c.getApplyEmail()));
                    preview.add(p);
                }
                result.put("success", true);
                result.put("dryRun", true);
                result.put("preview", preview);
                result.put("steps", steps);
                result.put("message", "演练模式：候选 " + preview.size() + " 家，未生成简历、未发信");
                return result;
            }

            int resumeCount = 0, mailCount = 0, skipped = 0, failed = 0;
            List<Map<String, Object>> processed = new ArrayList<>();

            for (FairCompanyEntity c : fresh) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("company", c.getCompanyName());
                row.put("score", c.getMatchScore());
                String jobTitle = firstJobTitle(c.getJobsJson());
                row.put("job", jobTitle);

                try {
                    String jd = buildJd(c, jobTitle);
                    Map<String, Object> tailor = resumeService.tailor(jd, jobTitle,
                            "自动-" + shortName(c.getCompanyName()) + "-" + jobTitle);
                    if (!Boolean.TRUE.equals(tailor.get("success"))) {
                        failed++;
                        saveTask(c, jobTitle, null, null, "FAILED",
                                "简历生成失败: " + tailor.get("message"));
                        row.put("status", "FAILED");
                        row.put("message", tailor.get("message"));
                        processed.add(row);
                        continue;
                    }
                    Long versionId = ((Number) tailor.get("versionId")).longValue();
                    String docxPath = String.valueOf(tailor.get("docxPath"));
                    resumeCount++;
                    row.put("resumeVersionId", versionId);

                    if (!notBlank(c.getApplyEmail())) {
                        skipped++;
                        saveTask(c, jobTitle, versionId, null, "RESUME_READY",
                                "简历已生成；该企业未公开投递邮箱，需现场或官网投递");
                        row.put("status", "RESUME_READY");
                        row.put("message", "无公开邮箱，简历已生成");
                        processed.add(row);
                        continue;
                    }

                    String attachName = attachName(jobTitle);
                    String subject = "【应聘】" + candidateName() + " - " + jobTitle + " - 2027届本科";
                    String body = buildApplyBody(c, jobTitle);
                    Map<String, Object> mail = mailService.queue(c.getApplyEmail(), subject, body, "HTML",
                            docxPath, attachName);
                    Long mailId = ((Number) mail.get("id")).longValue();
                    mailCount++;

                    Map<String, Object> rec = deliveryService.record("fair-email", c.getCompanyName(), jobTitle,
                            eventTitle(c.getEventId()), versionId, "SUCCESS",
                            "已向 " + c.getApplyEmail() + " 投递定制简历（邮件 #" + mailId + "）",
                            docxPath, attachName);

                    saveTask(c, jobTitle, versionId, mailId, "MAIL_QUEUED",
                            "投递邮件已入队 #" + mailId + "，收件人 " + c.getApplyEmail());
                    row.put("status", "MAIL_QUEUED");
                    row.put("mailId", mailId);
                    row.put("to", c.getApplyEmail());
                    row.put("deliveryId", rec.get("id"));
                } catch (Exception e) {
                    failed++;
                    log.warn("企业 {} 自动处理失败: {}", c.getCompanyName(), e.getMessage());
                    saveTask(c, jobTitle, null, null, "FAILED", e.getMessage());
                    row.put("status", "FAILED");
                    row.put("message", e.getMessage());
                }
                processed.add(row);
            }

            Map<String, Object> s3 = new LinkedHashMap<>();
            s3.put("step", "定制与投递");
            s3.put("resumeGenerated", resumeCount);
            s3.put("mailQueued", mailCount);
            s3.put("skipped", skipped);
            s3.put("failed", failed);
            steps.add(s3);

            // 尝试立即通过 SMTP 发出待发邮件
            Map<String, Object> send = null;
            if (mailCount > 0) {
                send = mailService.sendPending(20);
                Map<String, Object> s4 = new LinkedHashMap<>();
                s4.put("step", "SMTP 发送");
                s4.put("smtpReady", send.get("smtpReady"));
                s4.put("sent", send.get("sent"));
                s4.put("failed", send.get("failed"));
                if (send.get("message") != null) s4.put("message", send.get("message"));
                steps.add(s4);
            }

            result.put("success", true);
            result.put("steps", steps);
            result.put("resumeGenerated", resumeCount);
            result.put("mailQueued", mailCount);
            result.put("skippedNoEmail", skipped);
            result.put("failed", failed);
            result.put("smtp", send);
            result.put("processed", processed);
            result.put("elapsedMs", System.currentTimeMillis() - startedAt);
            result.put("message", String.format("完成：生成简历 %d 份，投递邮件 %d 封，无邮箱跳过 %d 家，失败 %d 家",
                    resumeCount, mailCount, skipped, failed));
            return result;
        } catch (Exception e) {
            log.error("自动流水线失败", e);
            result.put("success", false);
            result.put("message", "流水线异常: " + e.getMessage());
            result.put("steps", steps);
            return result;
        } finally {
            running = false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** 开关每日定时流水线 */
    public Map<String, Object> toggle(boolean enabled) {
        configService.updateConfig("AUTO_PIPELINE_ENABLED", enabled ? "1" : "0");
        Map<String, Object> r = status();
        r.put("message", enabled ? "已开启每日 08:30 自动流水线" : "已关闭定时流水线（仍可手动执行）");
        return r;
    }

    public Map<String, Object> tasks(int limit) {
        List<AutoApplyTaskEntity> list = taskMapper.selectList(new QueryWrapper<AutoApplyTaskEntity>()
                .orderByDesc("id").last("LIMIT " + (limit <= 0 ? 100 : limit)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("total", list.size());
        r.put("tasks", list);
        return r;
    }

    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("running", running);
        r.put("enabled", !"0".equals(configService.getConfigValue("AUTO_PIPELINE_ENABLED")));
        r.put("cron", "每天 08:30");
        r.put("smtpReady", mailService.smtpReady());
        r.put("totalTasks", taskMapper.selectCount(null));
        return r;
    }

    // ==================== 内部工具 ====================

    private void saveTask(FairCompanyEntity c, String jobTitle, Long versionId, Long mailId,
                          String status, String message) {
        AutoApplyTaskEntity t = new AutoApplyTaskEntity();
        t.setCompanyId(c.getId());
        t.setCompanyName(c.getCompanyName());
        t.setJobTitle(jobTitle);
        t.setEventId(c.getEventId());
        t.setResumeVersionId(versionId);
        t.setMailId(mailId);
        t.setStatus(status);
        t.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(t);
    }

    private String firstJobTitle(String jobsJson) {
        try {
            JSONArray arr = new JSONArray(jobsJson == null ? "[]" : jobsJson);
            if (arr.length() > 0) {
                String t = arr.getJSONObject(0).optString("title", "");
                if (!t.isBlank()) return t.trim();
            }
        } catch (Exception ignored) {
        }
        return "软件开发工程师";
    }

    /** 把 jobs_json 拼成一段可读 JD 供 AI 定制 */
    private String buildJd(FairCompanyEntity c, String jobTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("公司：").append(c.getCompanyName()).append('\n');
        sb.append("岗位：").append(jobTitle).append('\n');
        if (notBlank(c.getIndustry())) sb.append("行业：").append(c.getIndustry()).append('\n');
        try {
            JSONArray arr = new JSONArray(c.getJobsJson() == null ? "[]" : c.getJobsJson());
            for (int i = 0; i < arr.length() && i < 3; i++) {
                JSONObject j = arr.getJSONObject(i);
                sb.append("\n【岗位 ").append(i + 1).append("】")
                        .append(j.optString("title", "")).append('\n');
                String salary = j.optString("salary", "");
                if (!salary.isBlank()) sb.append("薪资：").append(salary).append('\n');
                String req = j.optString("requirement", "");
                if (!req.isBlank()) sb.append("要求：").append(req).append('\n');
            }
        } catch (Exception ignored) {
        }
        if (sb.toString().contains("要求：")) return sb.toString();
        sb.append("要求：熟悉 Java / Spring Boot / MySQL，了解大模型应用开发，有项目经验优先。\n");
        return sb.toString();
    }

    private String buildApplyBody(FairCompanyEntity c, String jobTitle) {
        return "<p>您好，</p>"
                + "<p>我是苏州科技大学计算机科学与技术专业 2027 届本科生，看到贵司在"
                + eventTitle(c.getEventId()) + "发布的「" + jobTitle + "」岗位，非常感兴趣。</p>"
                + "<p>我主攻 Java 后端与 AI Agent 方向，做过智能客服 Agent、RAG 检索增强、"
                + "Function Calling 等完整项目，具备 Spring Boot + MySQL 的后端开发经验。</p>"
                + "<p>附件是针对该岗位定制的简历，期待有机会进一步沟通。谢谢！</p>"
                + "<p>此邮件由求职自动化工具生成并发送。</p>";
    }

    private String eventTitle(Long eventId) {
        if (eventId == null) return "校园招聘";
        FairEventEntity e = eventMapper.selectById(eventId);
        return e == null || e.getTitle() == null ? "校园招聘" : e.getTitle();
    }

    private String candidateName() {
        String n = configService.getConfigValue("CANDIDATE_NAME");
        if (n != null && !n.isBlank()) return n.trim();
        return resumeService.candidateName();
    }

    private String attachName(String jobTitle) {
        return candidateName() + "-" + jobTitle.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + "-简历.docx";
    }

    private String shortName(String company) {
        if (company == null) return "企业";
        String s = company.replaceAll("(有限责任公司|股份有限公司|有限公司|集团|公司)$", "");
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

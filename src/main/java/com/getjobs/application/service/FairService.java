package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.FairCompanyEntity;
import com.getjobs.application.entity.FairEventEntity;
import com.getjobs.application.entity.FairSiteEntity;
import com.getjobs.application.entity.ResumeFactEntity;
import com.getjobs.application.mapper.FairCompanyMapper;
import com.getjobs.application.mapper.FairEventMapper;
import com.getjobs.application.mapper.FairSiteMapper;
import com.getjobs.application.mapper.ResumeFactMapper;
import com.getjobs.worker.fair.FairAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 招聘会采集、查询与岗位匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FairService {

    private final FairSiteMapper siteMapper;
    private final FairEventMapper eventMapper;
    private final FairCompanyMapper companyMapper;
    private final ResumeFactMapper factMapper;
    private final List<FairAdapter> adapters;

    /** 技能权重表：同时用于匹配打分与缺口提示 */
    private static final Map<String, Integer> SKILLS = new LinkedHashMap<>();
    static {
        String[][] pairs = {
                {"Java", "8"}, {"Spring Boot", "7"}, {"Spring", "5"}, {"MyBatis", "4"},
                {"MySQL", "5"}, {"Redis", "4"}, {"微服务", "4"}, {"分布式", "3"},
                {"Python", "4"}, {"AI Agent", "9"}, {"Agent", "7"}, {"大模型", "6"}, {"LLM", "6"},
                {"RAG", "7"}, {"Function Calling", "6"}, {"向量", "4"}, {"Prompt", "4"},
                {"Vue", "4"}, {"前端", "3"}, {"后端", "5"}, {"全栈", "3"},
                {"Docker", "3"}, {"Linux", "3"}, {"Git", "2"}, {"算法", "3"}, {"数据结构", "2"}
        };
        for (String[] p : pairs) SKILLS.put(p[0], Integer.parseInt(p[1]));
    }

    /** 简历事实为空时的兜底画像，保证首次使用也有匹配结果 */
    private static final String DEFAULT_PROFILE = "Java Spring Boot MyBatis MySQL Redis 微服务 后端 "
            + "AI Agent 大模型 LLM RAG Function Calling 向量 Prompt Vue 前端 Docker Linux Git 算法";

    // ==================== 站点 ====================

    public List<FairSiteEntity> listSites() {
        return siteMapper.selectList(new QueryWrapper<FairSiteEntity>().orderByAsc("id"));
    }

    // ==================== 采集 ====================

    public Map<String, Object> crawl(String requestedSite) {
        List<FairSiteEntity> sites;
        if (requestedSite == null || requestedSite.isBlank() || "all".equalsIgnoreCase(requestedSite)) {
            sites = siteMapper.selectList(new QueryWrapper<FairSiteEntity>().eq("enabled", 1));
        } else {
            sites = siteMapper.selectList(new QueryWrapper<FairSiteEntity>()
                    .eq("site_code", requestedSite).eq("enabled", 1));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int totalFetched = 0;
        int totalInserted = 0;
        int totalUpdated = 0;

        for (FairSiteEntity site : sites) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("siteCode", site.getSiteCode());
            d.put("name", site.getName());
            try {
                FairAdapter adapter = findAdapter(site.getAdapter());
                if (adapter == null) {
                    throw new IllegalStateException("未找到适配器: " + site.getAdapter());
                }
                List<FairAdapter.FairItem> items = adapter.fetch(site);
                int inserted = 0, updated = 0;
                for (FairAdapter.FairItem item : items) {
                    boolean isNew = upsertEvent(item);
                    if (isNew) inserted++; else updated++;
                }
                totalFetched += items.size();
                totalInserted += inserted;
                totalUpdated += updated;
                d.put("ok", true);
                d.put("fetched", items.size());
                d.put("inserted", inserted);
                d.put("updated", updated);
            } catch (Exception e) {
                log.warn("站点 {} 采集失败: {}", site.getSiteCode(), e.getMessage());
                d.put("ok", false);
                d.put("error", e.getMessage());
            }
            details.add(d);
        }

        result.put("success", true);
        result.put("fetched", totalFetched);
        result.put("inserted", totalInserted);
        result.put("updated", totalUpdated);
        result.put("details", details);
        return result;
    }

    /** 返回 true 表示新增，false 表示更新 */
    private boolean upsertEvent(FairAdapter.FairItem item) {
        FairEventEntity existing = eventMapper.selectOne(new QueryWrapper<FairEventEntity>()
                .eq("site_code", item.siteCode).eq("event_key", item.eventKey));
        LocalDateTime now = LocalDateTime.now();
        FairEventEntity e = existing != null ? existing : new FairEventEntity();
        e.setSiteCode(item.siteCode);
        e.setEventKey(item.eventKey);
        e.setTitle(item.title);
        e.setType(item.type);
        e.setStartTime(item.startTime != null ? LocalDateTime.parse(item.startTime) : null);
        e.setEndTime(item.endTime != null ? LocalDateTime.parse(item.endTime) : null);
        e.setLocation(item.location);
        e.setCity(item.city);
        e.setHost(item.host);
        e.setDetailUrl(item.detailUrl);
        e.setCompanyCount(item.companyCount != null ? item.companyCount : 0);
        e.setSummary(item.summary);
        e.setRawJson(item.rawJson);
        e.setUpdatedAt(now);
        if (existing == null) {
            e.setCreatedAt(now);
            eventMapper.insert(e);
        } else {
            eventMapper.updateById(e);
        }

        if (item.companies != null && !item.companies.isEmpty()) {
            companyMapper.delete(new QueryWrapper<FairCompanyEntity>().eq("event_id", e.getId()));
            String profile = resumeProfile();
            for (FairAdapter.CompanyItem c : item.companies) {
                FairCompanyEntity ce = new FairCompanyEntity();
                ce.setEventId(e.getId());
                ce.setCompanyName(c.companyName);
                ce.setIndustry(c.industry);
                ce.setScale(c.scale);
                ce.setJobsJson(c.jobsJson);
                ce.setApplyUrl(c.applyUrl);
                ce.setApplyEmail(c.applyEmail);
                int score = scoreCompany(c.jobsJson, profile);
                ce.setMatchScore(score);
                ce.setMatchReason(reason(c.jobsJson, profile));
                companyMapper.insert(ce);
            }
        }
        return existing == null;
    }

    // ==================== 查询 ====================

    public Map<String, Object> list(String city, String keyword, Integer days, Integer minScore) {
        QueryWrapper<FairEventEntity> qw = new QueryWrapper<>();
        if (city != null && !city.isBlank()) {
            qw.eq("city", city.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("title", k).or().like("summary", k));
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusHours(12);
        LocalDateTime to = now.plusDays(days != null && days > 0 ? days : 120);
        // 开始时间在窗口内，或结束时间还没到（进行中的活动也要展示）
        qw.and(w -> w.ge("start_time", from.toString()).or().ge("end_time", now.toString()));
        qw.le("start_time", to.toString());
        qw.orderByAsc("start_time");

        List<FairEventEntity> events = eventMapper.selectList(qw);
        String profile = resumeProfile();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FairEventEntity e : events) {
            List<FairCompanyEntity> companies = companyMapper.selectList(
                    new QueryWrapper<FairCompanyEntity>().eq("event_id", e.getId()));
            int score;
            List<String> matched = new ArrayList<>();
            if (!companies.isEmpty()) {
                score = companies.stream().mapToInt(c -> c.getMatchScore() == null ? 0 : c.getMatchScore()).max().orElse(0);
                for (FairCompanyEntity c : companies) {
                    if (c.getMatchScore() != null && c.getMatchScore() >= 40) {
                        matched.addAll(matchedKeywords(c.getJobsJson(), profile));
                    }
                }
            } else {
                score = scoreText(e.getTitle() + " " + safe(e.getSummary()), profile);
                matched = matchedKeywords(e.getTitle() + " " + safe(e.getSummary()), profile);
            }
            if (minScore != null && minScore > 0 && score < minScore) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("title", e.getTitle());
            row.put("type", e.getType());
            row.put("startTime", e.getStartTime());
            row.put("endTime", e.getEndTime());
            row.put("city", e.getCity());
            row.put("location", e.getLocation());
            row.put("host", e.getHost());
            row.put("companyCount", e.getCompanyCount());
            row.put("siteCode", e.getSiteCode());
            row.put("summary", e.getSummary());
            row.put("detailUrl", e.getDetailUrl());
            row.put("score", score);
            row.put("matchedKeywords", new ArrayList<>(new LinkedHashSet<>(matched)));
            row.put("companyCount", companies.size());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("total", rows.size());
        result.put("events", rows);
        return result;
    }

    public Map<String, Object> detail(Long id) {
        FairEventEntity e = eventMapper.selectById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (e == null) {
            result.put("success", false);
            result.put("message", "招聘会不存在");
            return result;
        }
        List<FairCompanyEntity> companies = companyMapper.selectList(
                new QueryWrapper<FairCompanyEntity>().eq("event_id", id).orderByDesc("match_score"));
        String profile = resumeProfile();

        List<Map<String, Object>> companyRows = new ArrayList<>();
        for (FairCompanyEntity c : companies) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("companyName", c.getCompanyName());
            row.put("industry", c.getIndustry());
            row.put("scale", c.getScale());
            row.put("jobsJson", c.getJobsJson());
            row.put("applyUrl", c.getApplyUrl());
            row.put("applyEmail", c.getApplyEmail());
            row.put("score", c.getMatchScore());
            row.put("reason", c.getMatchReason());
            companyRows.add(row);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", e.getId());
        event.put("title", e.getTitle());
        event.put("type", e.getType());
        event.put("startTime", e.getStartTime());
        event.put("endTime", e.getEndTime());
        event.put("city", e.getCity());
        event.put("location", e.getLocation());
        event.put("host", e.getHost());
        event.put("companyCount", e.getCompanyCount());
        event.put("summary", e.getSummary());
        event.put("detailUrl", e.getDetailUrl());
        event.put("siteCode", e.getSiteCode());

        List<Map<String, Object>> plan = new ArrayList<>();
        for (Map<String, Object> row : companyRows) {
            int s = (int) row.get("score");
            if (s >= 30) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("companyName", row.get("companyName"));
                p.put("score", s);
                p.put("jobs", row.get("jobsJson"));
                p.put("applyEmail", row.get("applyEmail"));
                p.put("applyUrl", row.get("applyUrl"));
                p.put("priority", s >= 60 ? "高" : s >= 40 ? "中" : "低");
                p.put("advice", s >= 60 ? "重点沟通，建议携带定制简历"
                        : s >= 40 ? "值得一聊，可现场了解岗位细节" : "顺路了解，优先级靠后");
                plan.add(p);
            }
        }

        result.put("success", true);
        result.put("event", event);
        result.put("companies", companyRows);
        result.put("battlePlan", plan);
        return result;
    }

    public Map<String, Object> matchEvent(Long id) {
        List<FairCompanyEntity> companies = companyMapper.selectList(
                new QueryWrapper<FairCompanyEntity>().eq("event_id", id));
        String profile = resumeProfile();
        for (FairCompanyEntity c : companies) {
            int score = scoreCompany(c.getJobsJson(), profile);
            c.setMatchScore(score);
            c.setMatchReason(reason(c.getJobsJson(), profile));
            companyMapper.updateById(c);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("updated", companies.size());
        return r;
    }

    // ==================== 匹配 ====================

    /** 简历画像：事实库标签 + 内容；为空时用兜底画像 */
    public String resumeProfile() {
        List<ResumeFactEntity> facts = factMapper.selectList(new QueryWrapper<>());
        if (facts.isEmpty()) return DEFAULT_PROFILE;
        StringBuilder sb = new StringBuilder(DEFAULT_PROFILE).append(' ');
        for (ResumeFactEntity f : facts) {
            if (f.getTags() != null) sb.append(f.getTags()).append(' ');
            if (f.getTitle() != null) sb.append(f.getTitle()).append(' ');
            if (f.getContent() != null) sb.append(f.getContent()).append(' ');
        }
        return sb.toString();
    }

    private int scoreCompany(String text, String profile) {
        return scoreText(text, profile);
    }

    /** 岗位文本与简历画像的技能重合度打分（0-98） */
    public int scoreText(String text, String profile) {
        if (text == null) return 0;
        int raw = 0;
        for (Map.Entry<String, Integer> e : SKILLS.entrySet()) {
            String skill = e.getKey();
            if (containsIgnoreCase(text, skill) && containsIgnoreCase(profile, skill)) {
                raw += e.getValue();
            }
        }
        int score = (int) Math.round(raw * 2.4);
        if (score > 98) score = 98;
        if (score == 0) {
            // 没有任何技能命中时，给一个基于文本长度的基础分，避免全 0
            score = Math.min(20, 5 + (text.length() / 60));
        }
        return score;
    }

    private String reason(String text, String profile) {
        List<String> hit = matchedKeywords(text, profile);
        List<String> gap = gapKeywords(text, profile);
        StringBuilder sb = new StringBuilder();
        sb.append(hit.isEmpty() ? "未见明显技能重合" : "命中：" + String.join("、", hit));
        if (!gap.isEmpty()) sb.append("；待补：" + String.join("、", gap.subList(0, Math.min(4, gap.size()))));
        return sb.toString();
    }

    public List<String> matchedKeywords(String text, String profile) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String skill : SKILLS.keySet()) {
            if (containsIgnoreCase(text, skill) && containsIgnoreCase(profile, skill)) out.add(skill);
        }
        return out;
    }

    public List<String> gapKeywords(String text, String profile) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String skill : SKILLS.keySet()) {
            if (containsIgnoreCase(text, skill) && !containsIgnoreCase(profile, skill)) out.add(skill);
        }
        return out;
    }

    private boolean containsIgnoreCase(String text, String key) {
        return text.toLowerCase().contains(key.toLowerCase());
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private FairAdapter findAdapter(String name) {
        for (FairAdapter a : adapters) {
            if (a.name().equalsIgnoreCase(name)) return a;
        }
        return null;
    }
}

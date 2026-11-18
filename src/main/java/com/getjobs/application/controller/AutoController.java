package com.getjobs.application.controller;

import com.getjobs.application.service.AutoApplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 自动流水线：采集 → 定制简历 → 投递邮件 */
@RestController
@RequestMapping("/api/auto")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AutoController {

    private final AutoApplyService autoApplyService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return autoApplyService.status();
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(required = false) Integer limit) {
        return autoApplyService.tasks(limit == null ? 100 : limit);
    }

    /** 开启/关闭每日定时流水线 */
    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestBody(required = false) Map<String, Object> body) {
        boolean on = body == null || body.get("enabled") == null
                || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return autoApplyService.toggle(on);
    }

    /**
     * 执行一轮流水线。
     * body: { crawl: true, maxItems: 3, minScore: 50, dryRun: false }
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> body) {
        boolean crawl = body == null || body.get("crawl") == null
                || Boolean.parseBoolean(String.valueOf(body.get("crawl")));
        int maxItems = body == null || body.get("maxItems") == null
                ? 3 : Integer.parseInt(String.valueOf(body.get("maxItems")));
        int minScore = body == null || body.get("minScore") == null
                ? 50 : Integer.parseInt(String.valueOf(body.get("minScore")));
        boolean dryRun = body != null && Boolean.parseBoolean(String.valueOf(body.get("dryRun")));
        return autoApplyService.run(crawl, Math.max(1, Math.min(20, maxItems)),
                Math.max(0, Math.min(98, minScore)), dryRun);
    }
}

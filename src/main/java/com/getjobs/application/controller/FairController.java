package com.getjobs.application.controller;

import com.getjobs.application.entity.FairSiteEntity;
import com.getjobs.application.service.FairService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 招聘会采集与匹配 */
@RestController
@RequestMapping("/api/fair")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FairController {

    private final FairService fairService;

    @GetMapping("/sites")
    public Map<String, Object> sites() {
        return Map.of("success", true, "sites", fairService.listSites());
    }

    @PostMapping("/crawl")
    public Map<String, Object> crawl(@RequestBody(required = false) Map<String, Object> body) {
        String site = body == null ? "all" : String.valueOf(body.getOrDefault("siteCode", "all"));
        return fairService.crawl(site);
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String city,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer days,
                                    @RequestParam(required = false) Integer minScore) {
        return fairService.list(city, keyword, days, minScore);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return fairService.detail(id);
    }

    @PostMapping("/{id}/match")
    public Map<String, Object> match(@PathVariable Long id) {
        return fairService.matchEvent(id);
    }
}

package com.getjobs.application.controller;

import com.getjobs.application.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 简历事实库与定制 */
@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ResumeController {

    private final ResumeService resumeService;

    @GetMapping("/facts")
    public Map<String, Object> facts() {
        return resumeService.listFacts();
    }

    @PostMapping("/import-facts")
    public Map<String, Object> importFacts(@RequestBody(required = false) Map<String, Object> body) {
        String path = body == null ? null : (String) body.get("path");
        Object resetObj = body == null ? null : body.get("reset");
        boolean reset = resetObj == null || Boolean.parseBoolean(String.valueOf(resetObj));
        return resumeService.importFacts(path, reset);
    }

    @GetMapping("/versions")
    public Map<String, Object> versions() {
        return resumeService.listVersions();
    }

    @PostMapping("/tailor")
    public Map<String, Object> tailor(@RequestBody Map<String, Object> body) {
        String jd = (String) body.get("jd");
        String targetJob = (String) body.get("targetJob");
        String name = (String) body.get("name");
        return resumeService.tailor(jd, targetJob, name);
    }
}

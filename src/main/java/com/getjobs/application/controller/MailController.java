package com.getjobs.application.controller;

import com.getjobs.application.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 邮件发件箱 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MailController {

    private final MailService mailService;

    @GetMapping("/outbox")
    public Map<String, Object> outbox(@RequestParam(required = false) String status) {
        return mailService.list(status);
    }

    @PostMapping("/report")
    public Map<String, Object> report() {
        return mailService.buildReport();
    }

    @PostMapping("/{id}/mark-sent")
    public Map<String, Object> markSent(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? null : (String) body.get("note");
        return mailService.markSent(id, note);
    }

    @PostMapping("/{id}/mark-failed")
    public Map<String, Object> markFailed(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        String error = body == null ? "unknown" : String.valueOf(body.getOrDefault("error", "unknown"));
        return mailService.markFailed(id, error);
    }

    /** 通过 SMTP 发送所有 PENDING 邮件 */
    @PostMapping("/send-pending")
    public Map<String, Object> sendPending(@RequestBody(required = false) Map<String, Object> body) {
        int limit = body == null || body.get("limit") == null
                ? 20 : Integer.parseInt(String.valueOf(body.get("limit")));
        return mailService.sendPending(limit);
    }

    @PostMapping("/{id}/send")
    public Map<String, Object> sendOne(@PathVariable Long id) {
        return mailService.sendById(id);
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return mailService.smtpConfig();
    }

    @PutMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        return mailService.saveSmtpConfig(body);
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, Object> body) {
        String to = body == null ? null : (String) body.get("to");
        return mailService.testSend(to);
    }
}

package com.getjobs.application.controller;

import com.getjobs.worker.utils.BotTester;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知自检接口：投递前先确认邮件能不能发出去。
 */
@RestController
@RequestMapping("/api/bot")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class BotController {

    private final BotTester botTester;

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> response = new HashMap<>();
        try {
            String error = botTester.test();
            if (error != null) {
                response.put("success", false);
                response.put("message", "发送失败：" + error);
            } else {
                response.put("success", true);
                response.put("message", "测试邮件已发送，请检查收件箱（含垃圾箱）");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("测试通知失败", e);
            response.put("success", false);
            response.put("message", "发送异常：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}

package com.getjobs.application.controller;

import com.getjobs.application.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 投递记录 */
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DeliveryController {

    private final DeliveryService deliveryService;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String channel,
                                    @RequestParam(required = false) Integer limit) {
        return deliveryService.list(channel, limit);
    }

    @PostMapping("/record")
    public Map<String, Object> record(@RequestBody Map<String, Object> body) {
        Long versionId = body.get("resumeVersionId") == null ? null
                : Long.parseLong(String.valueOf(body.get("resumeVersionId")));
        return deliveryService.record(
                (String) body.get("channel"),
                (String) body.get("company"),
                (String) body.get("job"),
                (String) body.get("source"),
                versionId,
                (String) body.get("status"),
                (String) body.get("result"));
    }
}

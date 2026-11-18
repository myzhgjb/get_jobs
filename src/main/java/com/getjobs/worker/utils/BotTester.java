package com.getjobs.worker.utils;

import com.getjobs.application.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 邮件通道自检：在真正投递前先确认 SMTP 能不能发出去。
 * 管理页面「测试通知」按钮走这里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotTester {

    private final ConfigService configService;

    /**
     * 发送一封测试邮件。
     *
     * @return null 表示发送成功；非 null 为失败原因
     */
    public String test() {
        // 每次测试都重新读一遍配置，避免改了授权码还要重启
        Bot bot = new Bot(configService);
        if (!bot.isMailUsableForTest()) {
            return "邮件配置不完整，请检查 MAIL_ENABLED / MAIL_HOST / MAIL_USERNAME / MAIL_AUTH_CODE / MAIL_TO 是否都已填写";
        }
        String result = bot.sendTestMail();
        if (result != null) {
            return result;
        }
        // 测试邮件不算业务通知，把节流计数清掉
        bot.resetThrottleForTest();
        return null;
    }
}

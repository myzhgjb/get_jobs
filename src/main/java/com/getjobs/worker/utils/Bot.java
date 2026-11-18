package com.getjobs.worker.utils;

import com.getjobs.application.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.fluent.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * 投递结果通知。
 * <p>
 * 两种通道，任选其一，都由数据库 config 表驱动：
 * <ul>
 *   <li>邮件（推荐）：MAIL_ENABLED=1 且 MAIL_HOST/MAIL_USERNAME/MAIL_AUTH_CODE/MAIL_TO 齐备时走 SMTP</li>
 *   <li>企业微信机器人：HOOK_URL 非空时走原有 webhook 报文</li>
 * </ul>
 * 邮件通道带节流：两次发送之间至少间隔 MAIL_THROTTLE_MS（默认 15 秒），
 * 避免短时间连续投递把邮箱打进垃圾箱。触发节流的邮件会合并计数，
 * 由 {@link #flushSkipped()} 汇总成一封补发。
 *
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Slf4j
@Service
public class Bot {

    private static volatile Bot INSTANCE;

    private final ConfigService configService;

    // ---- 邮件通道配置 ----
    private boolean mailEnabled;
    private String mailHost;
    private String mailPort;
    private String mailUsername;
    private String mailAuthCode;
    private String mailFrom;
    private String mailTo;
    private boolean mailSsl;
    private long mailThrottleMs;

    // ---- 企业微信通道配置（保留兼容）----
    private String hookUrl;
    private boolean isSend;

    /** 上次实际发信时间，用于节流 */
    private volatile long lastMailSentAt = 0L;
    /** 因节流被跳过的邮件条数 */
    private volatile int skippedCount = 0;
    /** 节流期间被跳过的内容摘要 */
    private final StringBuilder skippedBuffer = new StringBuilder();

    @Autowired
    public Bot(ConfigService configService) {
        this.configService = configService;
        INSTANCE = this;
        reloadConfig();
    }

    public void reloadConfig() {
        try {
            // ---- 邮件通道 ----
            this.mailEnabled = parseBool(configService.getConfigValue("MAIL_ENABLED"));
            this.mailHost = trim(configService.getConfigValue("MAIL_HOST"));
            this.mailPort = orDefault(trim(configService.getConfigValue("MAIL_PORT")), "465");
            this.mailUsername = trim(configService.getConfigValue("MAIL_USERNAME"));
            this.mailAuthCode = trim(configService.getConfigValue("MAIL_AUTH_CODE"));
            this.mailFrom = trim(configService.getConfigValue("MAIL_FROM"));
            this.mailTo = trim(configService.getConfigValue("MAIL_TO"));
            this.mailSsl = configService.getConfigValue("MAIL_SSL") == null
                    || parseBool(configService.getConfigValue("MAIL_SSL"));
            this.mailThrottleMs = parseLong(configService.getConfigValue("MAIL_THROTTLE_MS"), 15_000L);

            // ---- 企业微信通道 ----
            this.hookUrl = configService.getConfigValue("HOOK_URL");
            String sendFlag = configService.getConfigValue("BOT_IS_SEND");
            this.isSend = parseBool(sendFlag);

            if (mailUsable()) {
                log.info("邮件通知已启用 -> {}", mailTo);
            } else if (this.isSend && this.hookUrl != null && !this.hookUrl.isBlank()) {
                log.info("企业微信机器人通知已启用");
            } else {
                log.warn("通知未启用：邮件配置不完整且 HOOK_URL 为空，将不发送通知。");
            }
        } catch (Exception e) {
            log.error("加载通知配置失败: {}", e.getMessage());
            this.isSend = false;
            this.mailEnabled = false;
        }
    }

    /** 邮件通道配置是否齐备 */
    private boolean mailUsable() {
        return mailEnabled
                && notBlank(mailHost)
                && notBlank(mailUsername)
                && notBlank(mailAuthCode)
                && notBlank(mailTo);
    }

    // ================= 对外静态入口 =================

    /**
     * 发送带时间前缀的通知（投递过程中的实时通知，走节流）
     */
    public static void sendMessageByTime(String message) {
        Bot inst = INSTANCE;
        if (inst == null) {
            log.error("Bot 尚未初始化为 Spring Bean，忽略发送。");
            return;
        }
        inst.sendMessageByTimeInstance(message);
    }

    public void sendMessageByTimeInstance(String message) {
        if (!anyChannelUsable()) {
            return;
        }
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        sendMessageInstance(String.format("%s %s", currentTime, message));
    }

    /**
     * 发送通知（汇总类，不受节流限制）
     */
    public static void sendMessage(String message) {
        Bot inst = INSTANCE;
        if (inst == null) {
            log.warn("Bot 尚未初始化为 Spring Bean，忽略发送。");
            return;
        }
        inst.sendMessageInstance(message);
    }

    public void sendMessageInstance(String message) {
        if (!anyChannelUsable()) {
            return;
        }
        if (mailUsable()) {
            sendMail(message, false);
        } else {
            sendWeComHook(message);
        }
    }

    /**
     * 把节流期间被跳过的邮件补发成一封汇总邮件。
     * 投递流程结束时调用一次。
     */
    public static void flushSkipped() {
        Bot inst = INSTANCE;
        if (inst == null) {
            return;
        }
        inst.flushSkippedInstance();
    }

    public void flushSkippedInstance() {
        if (!mailUsable()) {
            return;
        }
        int count;
        String detail;
        synchronized (skippedBuffer) {
            count = skippedCount;
            detail = skippedBuffer.toString();
            skippedCount = 0;
            skippedBuffer.setLength(0);
        }
        if (count <= 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本次另有 ").append(count).append(" 条投递通知因发送过于密集被合并：\n\n");
        sb.append(detail);
        sendMail(sb.toString(), true);
        log.info("已补发 {} 条被节流合并的通知", count);
    }

    // ================= 实际发送 =================

    private void sendMail(String message, boolean force) {
        if (!force) {
            long now = System.currentTimeMillis();
            long gap = now - lastMailSentAt;
            if (lastMailSentAt != 0 && gap < mailThrottleMs) {
                synchronized (skippedBuffer) {
                    skippedCount++;
                    if (skippedBuffer.length() < 4000) {
                        skippedBuffer.append("· ").append(message).append('\n');
                    }
                }
                log.debug("通知过于密集，合并计数（{} 条待补发）", skippedCount);
                return;
            }
            lastMailSentAt = now;
        }

        try {
            doSendMail("Get Jobs 投递通知", message);
            log.info("邮件通知发送成功 -> {}", mailTo);
        } catch (Exception e) {
            log.error("邮件通知发送失败: {}", e.getMessage());
        }
    }

    /**
     * 真正建立 SMTP 连接并投递一封邮件。
     *
     * @return null 成功；非 null 为失败原因
     */
    private String doSendMail(String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", mailHost);
        props.put("mail.smtp.port", mailPort);
        props.put("mail.smtp.auth", "true");
        if (mailSsl) {
            // 465 端口走隐式 SSL；25/587 走 STARTTLS
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        // 避免连接挂死
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.mime.charset", StandardCharsets.UTF_8.name());

        final String user = mailUsername;
        final String code = mailAuthCode;
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, code);
            }
        });

        MimeMessage mime = new MimeMessage(session);
        String from = notBlank(mailFrom) ? mailFrom : user;
        mime.setFrom(new InternetAddress(from));
        // MAIL_TO 支持逗号分隔的多个收件人
        mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(mailTo));
        mime.setSubject(subject);
        mime.setText(body, StandardCharsets.UTF_8.name());

        Transport.send(mime);
        return null;
    }

    private void sendWeComHook(String message) {
        if (!isSend || hookUrl == null || hookUrl.isBlank()) {
            log.warn("HOOK_URL 未设置，无法推送消息。");
            return;
        }
        try {
            // 原文是字符串直接拼 JSON，消息里带引号或换行会拼出非法 JSON，这里做转义
            String escaped = message.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "")
                    .replace("\n", "\\n");
            String response = Request.post(hookUrl)
                    .bodyString("{\"msgtype\": \"text\", \"text\": {\"content\": \"" + escaped + "\"}}",
                            org.apache.hc.core5.http.ContentType.APPLICATION_JSON)
                    .execute()
                    .returnContent()
                    .asString();
            log.info("企业微信消息推送成功: {}", response);
        } catch (Exception e) {
            log.error("企业微信消息推送失败: {}", e.getMessage());
        }
    }

    // ================= 工具方法 =================

    /** 供自检组件判断邮件通道是否可用 */
    public boolean isMailUsableForTest() {
        return mailUsable();
    }

    /**
     * 发一封测试邮件。
     *
     * @return null 表示成功；非 null 为失败原因
     */
    public String sendTestMail() {
        if (!mailUsable()) {
            return "邮件配置不完整";
        }
        try {
            String body = "这是一封来自 Get Jobs 自动投递助手的测试邮件。\n\n"
                    + "如果你收到了，说明邮件通道配置成功，\n"
                    + "之后每次成功投递简历都会收到一封通知。\n\n"
                    + "发送时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n";
            lastMailSentAt = System.currentTimeMillis();
            return doSendMail("Get Jobs 通知测试", body);
        } catch (Exception e) {
            log.error("测试邮件发送失败: {}", e.getMessage());
            return e.getMessage();
        }
    }

    /** 测试邮件不应占用节流配额 */
    public void resetThrottleForTest() {
        this.lastMailSentAt = 0L;
    }

    private boolean anyChannelUsable() {
        return mailUsable() || (isSend && notBlank(hookUrl));
    }

    private static boolean parseBool(String v) {
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    private static String orDefault(String v, String def) {
        return notBlank(v) ? v : def;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}

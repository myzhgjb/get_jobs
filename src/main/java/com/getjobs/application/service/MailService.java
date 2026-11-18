package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.DeliveryRecordEntity;
import com.getjobs.application.entity.MailOutboxEntity;
import com.getjobs.application.mapper.DeliveryRecordMapper;
import com.getjobs.application.mapper.MailOutboxMapper;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件发件箱：投递回执与汇总报告统一入队，并可直接通过 SMTP 发出。
 * <p>
 * 配置全部来自 config 表：MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_AUTH_CODE /
 * MAIL_FROM / MAIL_TO / MAIL_SSL / MAIL_ENABLED。未配置授权码时邮件保持 PENDING，
 * 可通过外部通道（如 Agent Mail）代发后回写状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final MailOutboxMapper outboxMapper;
    private final DeliveryRecordMapper deliveryMapper;
    private final ConfigService configService;

    /** 回执收件地址：优先 MAIL_FEEDBACK_TO，其次 MAIL_TO；都未配置时返回空串，由调用方提示用户配置 */
    public String defaultRecipient() {
        String to = configService.getConfigValue("MAIL_FEEDBACK_TO");
        if (to == null || to.isBlank()) to = configService.getConfigValue("MAIL_TO");
        return (to == null || to.isBlank()) ? "" : to.trim();
    }

    public Map<String, Object> queue(String to, String subject, String body, String format) {
        return queue(to, subject, body, format, null, null);
    }

    public Map<String, Object> queue(String to, String subject, String body, String format,
                                     String attachmentPath, String attachName) {
        MailOutboxEntity m = new MailOutboxEntity();
        m.setToAddr((to == null || to.isBlank()) ? defaultRecipient() : to.trim());
        m.setSubject(subject);
        m.setBody(body);
        m.setBodyFormat(format == null ? "HTML" : format);
        m.setStatus("PENDING");
        m.setAttachmentPath(attachmentPath);
        m.setAttachName(attachName);
        m.setCreatedAt(LocalDateTime.now());
        outboxMapper.insert(m);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("id", m.getId());
        r.put("to", m.getToAddr());
        return r;
    }

    public Map<String, Object> list(String status) {
        QueryWrapper<MailOutboxEntity> qw = new QueryWrapper<>();
        if (status != null && !status.isBlank()) qw.eq("status", status);
        qw.orderByDesc("id");
        List<MailOutboxEntity> list = outboxMapper.selectList(qw);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("total", list.size());
        r.put("mails", list);
        return r;
    }

    public Map<String, Object> markSent(Long id, String note) {
        MailOutboxEntity m = outboxMapper.selectById(id);
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("success", false);
            r.put("message", "邮件不存在");
            return r;
        }
        m.setStatus("SENT");
        m.setSentAt(LocalDateTime.now());
        m.setError(note);
        outboxMapper.updateById(m);
        r.put("success", true);
        r.put("id", id);
        return r;
    }

    public Map<String, Object> markFailed(Long id, String error) {
        MailOutboxEntity m = outboxMapper.selectById(id);
        Map<String, Object> r = new LinkedHashMap<>();
        if (m == null) {
            r.put("success", false);
            r.put("message", "邮件不存在");
            return r;
        }
        m.setStatus("FAILED");
        m.setError(error);
        outboxMapper.updateById(m);
        r.put("success", true);
        return r;
    }

    // ==================== SMTP 发送 ====================

    /** SMTP 配置是否齐备 */
    public boolean smtpReady() {
        return notBlank(cfg("MAIL_HOST")) && notBlank(cfg("MAIL_USERNAME")) && notBlank(cfg("MAIL_AUTH_CODE"));
    }

    /**
     * 发送所有 PENDING 邮件。SMTP 未配置时不做无意义的尝试，直接返回原因。
     */
    public Map<String, Object> sendPending(int limit) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!smtpReady()) {
            r.put("success", false);
            r.put("message", "SMTP 未配置：请先填写 MAIL_HOST / MAIL_USERNAME / MAIL_AUTH_CODE");
            r.put("smtpReady", false);
            return r;
        }
        List<MailOutboxEntity> pending = outboxMapper.selectList(new QueryWrapper<MailOutboxEntity>()
                .eq("status", "PENDING").orderByAsc("id")
                .last("LIMIT " + (limit <= 0 ? 20 : limit)));

        int sent = 0, failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (MailOutboxEntity m : pending) {
            String err = sendOne(m);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", m.getId());
            d.put("subject", m.getSubject());
            d.put("to", m.getToAddr());
            if (err == null) {
                m.setStatus("SENT");
                m.setSentAt(LocalDateTime.now());
                m.setError(null);
                outboxMapper.updateById(m);
                sent++;
                d.put("ok", true);
            } else {
                m.setStatus("FAILED");
                m.setError(err.length() > 500 ? err.substring(0, 500) : err);
                outboxMapper.updateById(m);
                failed++;
                d.put("ok", false);
                d.put("error", err);
            }
            details.add(d);
        }
        r.put("success", failed == 0);
        r.put("smtpReady", true);
        r.put("total", pending.size());
        r.put("sent", sent);
        r.put("failed", failed);
        r.put("details", details);
        return r;
    }

    public Map<String, Object> sendById(Long id) {
        Map<String, Object> r = new LinkedHashMap<>();
        MailOutboxEntity m = outboxMapper.selectById(id);
        if (m == null) {
            r.put("success", false);
            r.put("message", "邮件不存在");
            return r;
        }
        String err = sendOne(m);
        if (err == null) {
            m.setStatus("SENT");
            m.setSentAt(LocalDateTime.now());
            m.setError(null);
            outboxMapper.updateById(m);
            r.put("success", true);
        } else {
            m.setStatus("FAILED");
            m.setError(err.length() > 500 ? err.substring(0, 500) : err);
            outboxMapper.updateById(m);
            r.put("success", false);
            r.put("message", err);
        }
        return r;
    }

    /** @return null 表示成功，非 null 为失败原因 */
    private String sendOne(MailOutboxEntity m) {
        try {
            String host = cfg("MAIL_HOST");
            String port = orDefault(cfg("MAIL_PORT"), "465");
            String user = cfg("MAIL_USERNAME");
            String code = cfg("MAIL_AUTH_CODE");
            String from = orDefault(cfg("MAIL_FROM"), user);
            boolean ssl = !"false".equalsIgnoreCase(orDefault(cfg("MAIL_SSL"), "true"));

            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", port);
            props.put("mail.smtp.auth", "true");
            if (ssl) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "20000");
            props.put("mail.mime.charset", StandardCharsets.UTF_8.name());

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, code);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(m.getToAddr()));
            msg.setSubject(m.getSubject(), StandardCharsets.UTF_8.name());

            boolean html = "HTML".equalsIgnoreCase(m.getBodyFormat());
            File att = (m.getAttachmentPath() == null || m.getAttachmentPath().isBlank())
                    ? null : new File(m.getAttachmentPath());
            if (att != null && !att.exists()) {
                return "附件不存在: " + m.getAttachmentPath();
            }

            if (att == null && !html) {
                msg.setText(m.getBody(), StandardCharsets.UTF_8.name());
            } else {
                MimeMultipart multipart = new MimeMultipart("mixed");
                MimeBodyPart bodyPart = new MimeBodyPart();
                bodyPart.setContent(m.getBody() == null ? "" : m.getBody(),
                        (html ? "text/html" : "text/plain") + "; charset=UTF-8");
                multipart.addBodyPart(bodyPart);
                if (att != null) {
                    MimeBodyPart filePart = new MimeBodyPart();
                    filePart.attachFile(att);
                    String name = notBlank(m.getAttachName()) ? m.getAttachName() : att.getName();
                    filePart.setFileName(MimeUtility.encodeText(name, StandardCharsets.UTF_8.name(), null));
                    multipart.addBodyPart(filePart);
                }
                msg.setContent(multipart);
            }

            Transport.send(msg);
            log.info("邮件已发送 #{} -> {} | {}", m.getId(), m.getToAddr(), m.getSubject());
            return null;
        } catch (Exception e) {
            log.error("邮件发送失败 #{}: {}", m.getId(), e.getMessage());
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** 发送一封测试邮件，用于验证 SMTP 配置 */
    public Map<String, Object> testSend(String to) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!smtpReady()) {
            r.put("success", false);
            r.put("message", "SMTP 未配置：请先填写 MAIL_HOST / MAIL_USERNAME / MAIL_AUTH_CODE");
            return r;
        }
        String addr = (to == null || to.isBlank()) ? defaultRecipient() : to.trim();
        MailOutboxEntity m = new MailOutboxEntity();
        m.setId(0L);
        m.setToAddr(addr);
        m.setSubject("[get_jobs] SMTP 测试邮件 " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        m.setBody("<p>这是一封测试邮件，收到即表示 SMTP 配置正确。</p>"
                + "<p>发送时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "</p>");
        m.setBodyFormat("HTML");
        String err = sendOne(m);
        r.put("success", err == null);
        if (err != null) r.put("message", err);
        r.put("to", addr);
        return r;
    }

    /** 读取 SMTP 配置，授权码脱敏 */
    public Map<String, Object> smtpConfig() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("host", orDefault(cfg("MAIL_HOST"), ""));
        r.put("port", orDefault(cfg("MAIL_PORT"), "465"));
        r.put("username", orDefault(cfg("MAIL_USERNAME"), ""));
        String code = cfg("MAIL_AUTH_CODE");
        r.put("authCodeSet", notBlank(code));
        r.put("authCodeMasked", notBlank(code) ? "****" + code.substring(Math.max(0, code.length() - 4)) : "");
        r.put("from", orDefault(cfg("MAIL_FROM"), ""));
        r.put("to", orDefault(cfg("MAIL_TO"), defaultRecipient()));
        r.put("feedbackTo", defaultRecipient());
        r.put("ssl", !"false".equalsIgnoreCase(orDefault(cfg("MAIL_SSL"), "true")));
        r.put("enabled", "1".equals(cfg("MAIL_ENABLED")) || "true".equalsIgnoreCase(orDefault(cfg("MAIL_ENABLED"), "")));
        r.put("smtpReady", smtpReady());
        r.put("success", true);
        return r;
    }

    /** 保存 SMTP 配置；authCode 传空表示保持原值不变 */
    public Map<String, Object> saveSmtpConfig(Map<String, Object> body) {
        if (body == null) body = Map.of();
        writeIfPresent(body, "host", "MAIL_HOST");
        writeIfPresent(body, "port", "MAIL_PORT");
        writeIfPresent(body, "username", "MAIL_USERNAME");
        writeIfPresent(body, "from", "MAIL_FROM");
        writeIfPresent(body, "to", "MAIL_TO");
        writeIfPresent(body, "feedbackTo", "MAIL_FEEDBACK_TO");
        if (body.get("authCode") != null && !String.valueOf(body.get("authCode")).isBlank()) {
            configService.updateConfig("MAIL_AUTH_CODE", String.valueOf(body.get("authCode")).trim());
        }
        if (body.get("ssl") != null) {
            configService.updateConfig("MAIL_SSL", String.valueOf(body.get("ssl")));
        }
        if (body.get("enabled") != null) {
            configService.updateConfig("MAIL_ENABLED", toFlag(body.get("enabled")));
        }
        Map<String, Object> r = smtpConfig();
        r.put("saved", true);
        return r;
    }

    private void writeIfPresent(Map<String, Object> body, String key, String configKey) {
        Object v = body.get(key);
        if (v != null) configService.updateConfig(configKey, String.valueOf(v).trim());
    }

    private String toFlag(Object v) {
        if (v instanceof Boolean b) return b ? "1" : "0";
        String s = String.valueOf(v);
        return ("1".equals(s) || "true".equalsIgnoreCase(s)) ? "1" : "0";
    }

    // ==================== 回执与汇总 ====================

    /** 单条投递回执 */
    public void queueDeliveryReceipt(DeliveryRecordEntity d, String resumeName) {
        queueDeliveryReceipt(d, resumeName, null, null);
    }

    public void queueDeliveryReceipt(DeliveryRecordEntity d, String resumeName,
                                     String attachmentPath, String attachName) {
        String subject = "[投递回执] " + nullSafe(d.getCompany()) + " - " + nullSafe(d.getJob());
        String body = "<h3>投递回执</h3><table cellpadding=\"6\" style=\"border-collapse:collapse\">"
                + row("公司", d.getCompany())
                + row("岗位", d.getJob())
                + row("渠道", d.getChannel())
                + row("来源", d.getSource())
                + row("简历版本", resumeName)
                + row("状态", d.getStatus())
                + row("结果", d.getResult())
                + row("时间", d.getCreatedAt() == null ? "" : d.getCreatedAt().toString())
                + "</table><p style=\"color:#888;font-size:12px\">本邮件由 get_jobs 自动发送</p>";
        queue(null, subject, body, "HTML", attachmentPath, attachName);
    }

    /** 汇总报告：所有投递记录 + 统计 */
    public Map<String, Object> buildReport() {
        List<DeliveryRecordEntity> all = deliveryMapper.selectList(
                new QueryWrapper<DeliveryRecordEntity>().orderByDesc("id"));
        long success = all.stream().filter(x -> "SUCCESS".equalsIgnoreCase(nullSafe(x.getStatus()))).count();
        long failed = all.stream().filter(x -> "FAILED".equalsIgnoreCase(nullSafe(x.getStatus()))).count();
        long pending = all.size() - success - failed;

        StringBuilder sb = new StringBuilder();
        sb.append("<h3>get_jobs 投递汇总</h3>")
                .append("<p>生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("<br/>总计：").append(all.size()).append(" 条，成功 ").append(success)
                .append("，失败 ").append(failed).append("，处理中 ").append(pending).append("</p>");
        sb.append("<table cellpadding=\"6\" style=\"border-collapse:collapse;border:1px solid #ddd\">")
                .append("<tr style=\"background:#f5f5f5\"><th>时间</th><th>渠道</th><th>公司</th><th>岗位</th><th>状态</th><th>备注</th></tr>");
        for (DeliveryRecordEntity d : all) {
            sb.append("<tr>")
                    .append(td(d.getCreatedAt() == null ? "" : d.getCreatedAt().toString())).append(td(d.getChannel()))
                    .append(td(d.getCompany())).append(td(d.getJob())).append(td(d.getStatus()))
                    .append(td(d.getResult())).append("</tr>");
        }
        sb.append("</table><p style=\"color:#888;font-size:12px\">本邮件由 get_jobs 自动发送</p>");

        String subject = "[get_jobs] 投递汇总 - "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return queue(null, subject, sb.toString(), "HTML");
    }

    private String row(String k, String v) {
        return "<tr><td style=\"color:#666\">" + k + "</td><td>" + nullSafe(v) + "</td></tr>";
    }

    private String td(String v) {
        return "<td style=\"border:1px solid #eee;padding:4px 8px\">" + nullSafe(v) + "</td>";
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String cfg(String key) {
        return configService.getConfigValue(key);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String orDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }
}

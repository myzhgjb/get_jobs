package com.getjobs.application.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 新增模块的建表与种子数据。
 * SQLite 没有迁移工具，这里用 CREATE TABLE IF NOT EXISTS 保证幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS fair_site (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, site_code TEXT UNIQUE, name TEXT, base_url TEXT, " +
                    "adapter TEXT, enabled INTEGER DEFAULT 1, config_json TEXT, created_at TEXT, updated_at TEXT)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS fair_event (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, site_code TEXT, event_key TEXT, title TEXT, type TEXT, " +
                    "start_time TEXT, end_time TEXT, location TEXT, city TEXT, host TEXT, detail_url TEXT, " +
                    "company_count INTEGER DEFAULT 0, summary TEXT, raw_json TEXT, created_at TEXT, updated_at TEXT, " +
                    "UNIQUE(site_code, event_key))");

            jdbc.execute("CREATE TABLE IF NOT EXISTS fair_company (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, event_id INTEGER, company_name TEXT, industry TEXT, " +
                    "scale TEXT, jobs_json TEXT, apply_url TEXT, apply_email TEXT, match_score INTEGER DEFAULT 0, " +
                    "match_reason TEXT)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS resume_fact (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, title TEXT, content TEXT, tags TEXT, " +
                    "start_date TEXT, end_date TEXT, verified INTEGER DEFAULT 1)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS resume_version (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, target_job TEXT, jd_text TEXT, content_json TEXT, " +
                    "docx_path TEXT, pdf_path TEXT, created_at TEXT)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS delivery_record (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, channel TEXT, company TEXT, job TEXT, source TEXT, " +
                    "resume_version_id INTEGER, status TEXT, result TEXT, created_at TEXT)");

            jdbc.execute("CREATE TABLE IF NOT EXISTS mail_outbox (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, to_addr TEXT, subject TEXT, body TEXT, body_format TEXT, " +
                    "status TEXT, error TEXT, attachment_path TEXT, attach_name TEXT, sent_at TEXT, created_at TEXT)");

            // 自动流水线任务：一家企业一条，company_id 唯一保证幂等
            jdbc.execute("CREATE TABLE IF NOT EXISTS auto_apply_task (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, company_id INTEGER UNIQUE, company_name TEXT, " +
                    "job_title TEXT, event_id INTEGER, resume_version_id INTEGER, mail_id INTEGER, " +
                    "status TEXT, message TEXT, created_at TEXT, updated_at TEXT)");

            // 老库补列（SQLite 不支持 IF NOT EXISTS 加列，用 PRAGMA 判断）
            ensureColumn("mail_outbox", "attachment_path", "TEXT");
            ensureColumn("mail_outbox", "attach_name", "TEXT");

            String now = LocalDateTime.now().toString();
            jdbc.update("INSERT OR IGNORE INTO fair_site(site_code,name,base_url,adapter,enabled,config_json,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    "ncss", "国家大学生就业服务平台（24365）", "https://www.ncss.cn", "ncss", 1, "{}", now, now);
            jdbc.update("INSERT OR IGNORE INTO fair_site(site_code,name,base_url,adapter,enabled,config_json,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?)",
                    "sample", "示例数据源（本地测试）", "", "sample", 1, "{}", now, now);

            log.info("✓ 招聘会/简历/投递/邮件 相关表已就绪");
        } catch (Exception e) {
            log.error("初始化扩展表失败: {}", e.getMessage(), e);
        }
    }

    /** 幂等补列：列已存在时静默跳过 */
    private void ensureColumn(String table, String column, String type) {
        try {
            Boolean exists = jdbc.query("PRAGMA table_info(" + table + ")",
                    rs -> {
                        while (rs.next()) {
                            if (column.equalsIgnoreCase(rs.getString("name"))) return true;
                        }
                        return false;
                    });
            if (Boolean.TRUE.equals(exists)) return;
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("✓ 已为 {}.{} 补列", table, column);
        } catch (Exception e) {
            log.warn("补列失败 {}.{}: {}", table, column, e.getMessage());
        }
    }
}

-- get_jobs 扩展模块（幂等建表）
CREATE TABLE IF NOT EXISTS fair_site (
    id INTEGER PRIMARY KEY AUTOINCREMENT, site_code TEXT UNIQUE, name TEXT, base_url TEXT,
    adapter TEXT, enabled INTEGER DEFAULT 1, config_json TEXT, created_at TEXT, updated_at TEXT);

CREATE TABLE IF NOT EXISTS fair_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT, site_code TEXT, event_key TEXT, title TEXT, type TEXT,
    start_time TEXT, end_time TEXT, location TEXT, city TEXT, host TEXT, detail_url TEXT,
    company_count INTEGER DEFAULT 0, summary TEXT, raw_json TEXT, created_at TEXT, updated_at TEXT,
    UNIQUE(site_code, event_key));

CREATE TABLE IF NOT EXISTS fair_company (
    id INTEGER PRIMARY KEY AUTOINCREMENT, event_id INTEGER, company_name TEXT, industry TEXT,
    scale TEXT, jobs_json TEXT, apply_url TEXT, apply_email TEXT, match_score INTEGER DEFAULT 0,
    match_reason TEXT);

CREATE TABLE IF NOT EXISTS resume_fact (
    id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, title TEXT, content TEXT, tags TEXT,
    start_date TEXT, end_date TEXT, verified INTEGER DEFAULT 1);

CREATE TABLE IF NOT EXISTS resume_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, target_job TEXT, jd_text TEXT, content_json TEXT,
    docx_path TEXT, pdf_path TEXT, created_at TEXT);

CREATE TABLE IF NOT EXISTS delivery_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT, channel TEXT, company TEXT, job TEXT, source TEXT,
    resume_version_id INTEGER, status TEXT, result TEXT, created_at TEXT);

CREATE TABLE IF NOT EXISTS mail_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT, to_addr TEXT, subject TEXT, body TEXT, body_format TEXT,
    status TEXT, error TEXT, attachment_path TEXT, attach_name TEXT, sent_at TEXT, created_at TEXT);

CREATE TABLE IF NOT EXISTS auto_apply_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT, company_id INTEGER UNIQUE, company_name TEXT,
    job_title TEXT, event_id INTEGER, resume_version_id INTEGER, mail_id INTEGER,
    status TEXT, message TEXT, created_at TEXT, updated_at TEXT);

INSERT OR IGNORE INTO fair_site(site_code,name,base_url,adapter,enabled,config_json,created_at,updated_at)
VALUES('ncss','国家大学生就业服务平台（24365）','https://www.ncss.cn','ncss',1,'{}',datetime('now'),datetime('now'));

INSERT OR IGNORE INTO fair_site(site_code,name,base_url,adapter,enabled,config_json,created_at,updated_at)
VALUES('sample','示例数据源（本地测试）','','sample',1,'{}',datetime('now'),datetime('now'));

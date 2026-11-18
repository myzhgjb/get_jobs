package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.ResumeFactEntity;
import com.getjobs.application.entity.ResumeVersionEntity;
import com.getjobs.application.mapper.ResumeFactMapper;
import com.getjobs.application.mapper.ResumeVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 简历事实库、按 JD 定制、docx 导出。
 * 原则：AI 只能重组和改写事实，不得新增经历。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeFactMapper factMapper;
    private final ResumeVersionMapper versionMapper;
    private final AiService aiService;
    private final FairService fairService;
    private final ConfigService configService;

    /** 主简历默认路径，可用配置项 RESUME_SOURCE_PATH 覆盖 */
    private static final String DEFAULT_RESUME = "data/resume.docx";
    private static final String OUTPUT_DIR = "data/resumes";

    // ==================== 事实库 ====================

    public Map<String, Object> listFacts() {
        List<ResumeFactEntity> facts = factMapper.selectList(new QueryWrapper<ResumeFactEntity>().orderByAsc("id"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("total", facts.size());
        r.put("facts", facts);
        return r;
    }

    /** 从 docx 导入事实库（幂等：默认先清空） */
    public Map<String, Object> importFacts(String path, boolean reset) {
        String file = path;
        if (file == null || file.isBlank()) {
            String configured = configService.getConfigValue("RESUME_SOURCE_PATH");
            file = (configured == null || configured.isBlank()) ? DEFAULT_RESUME : configured.trim();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        File f = new File(file);
        if (!f.exists()) {
            r.put("success", false);
            r.put("message", "简历文件不存在: " + file);
            return r;
        }
        try {
            String text = extractDocxText(f);
            if (reset) {
                factMapper.delete(new QueryWrapper<>());
            }
            List<ResumeFactEntity> facts = splitToFacts(text);
            for (ResumeFactEntity fact : facts) {
                factMapper.insert(fact);
            }
            r.put("success", true);
            r.put("source", file);
            r.put("imported", facts.size());
            r.put("categories", facts.stream().map(ResumeFactEntity::getCategory).distinct().toList());
            r.put("chars", text.length());
            return r;
        } catch (Exception e) {
            log.error("导入简历事实失败", e);
            r.put("success", false);
            r.put("message", "导入失败: " + e.getMessage());
            return r;
        }
    }

    /** 解压 docx 读取 word/document.xml，按段落抽取文本 */
    public String extractDocxText(File file) throws Exception {
        String xml = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    xml = bos.toString(StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        if (xml == null) throw new IllegalStateException("docx 中没有 word/document.xml");

        // 段落边界 -> 换行，<w:t> 内容拼接（注意 <w:t> 只能匹配自身，不能误吃 <w:tab/>）
        xml = xml.replace("</w:p>", "\n");
        Matcher m = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>", Pattern.DOTALL).matcher(xml);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            // 保持段落之间的换行
            String between = xml.substring(last, m.start());
            long breaks = between.chars().filter(ch -> ch == '\n').count();
            for (int i = 0; i < breaks; i++) sb.append('\n');
            sb.append(unescapeXml(m.group(1)));
            last = m.end();
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private List<ResumeFactEntity> splitToFacts(String text) {
        List<ResumeFactEntity> facts = new ArrayList<>();
        String[] lines = text.split("\n");
        String current = "其他";
        StringBuilder buf = new StringBuilder();
        List<String> titles = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String section = detectSection(line);
            if (section != null) {
                flushFact(facts, current, titles, buf);
                current = section;
                titles = new ArrayList<>();
                buf = new StringBuilder();
                continue;
            }
            if (buf.length() > 0) buf.append('\n');
            buf.append(line);
            if (line.length() <= 30) titles.add(line);
        }
        flushFact(facts, current, titles, buf);

        // 基本信息单独存一条，便于邮件/简历抬头
        String head = text.length() > 260 ? text.substring(0, 260) : text;
        facts.add(0, fact("基本信息", "个人基本信息", head, "姓名,联系方式,教育背景"));
        return facts;
    }

    private void flushFact(List<ResumeFactEntity> facts, String category, List<String> titles, StringBuilder buf) {
        if (buf.length() == 0) return;
        String title = titles.isEmpty() ? category : titles.get(0);
        String content = buf.toString().trim();
        if (content.isEmpty()) return;
        facts.add(fact(category, title, content, extractTags(content)));
    }

    private ResumeFactEntity fact(String category, String title, String content, String tags) {
        ResumeFactEntity f = new ResumeFactEntity();
        f.setCategory(category);
        f.setTitle(title);
        f.setContent(content.length() > 4000 ? content.substring(0, 4000) : content);
        f.setTags(tags);
        f.setVerified(1);
        return f;
    }

    private String detectSection(String line) {
        String t = line.replaceAll("[\\s:：\\[\\]【】]", "");
        if (t.length() > 14) return null;
        if (t.contains("教育背景") || t.contains("教育经历") || t.contains("教育情况")) return "教育";
        if (t.contains("专业技能") || t.contains("技能特长") || t.contains("技能清单") || t.equals("技能")) return "技能";
        if (t.contains("项目经历") || t.contains("项目经验") || t.contains("项目实践")) return "项目";
        if (t.contains("实习经历") || t.contains("工作经历") || t.contains("实习经验")) return "实习";
        if (t.contains("荣誉奖项") || t.contains("获奖情况") || t.contains("证书")) return "荣誉";
        if (t.contains("自我评价") || t.contains("个人总结")) return "自我评价";
        return null;
    }

    private String extractTags(String content) {
        List<String> tags = new ArrayList<>();
        for (String skill : new String[]{"Java", "Spring Boot", "Spring", "MyBatis", "MySQL", "Redis", "微服务",
                "Python", "AI Agent", "大模型", "LLM", "RAG", "Function Calling", "Vue", "前端", "后端",
                "Docker", "Linux", "Git", "算法", "数据结构", "MongoDB", "Elasticsearch", "Kafka"}) {
            if (content.toLowerCase().contains(skill.toLowerCase())) tags.add(skill);
        }
        return String.join(",", tags);
    }

    // ==================== 定制 ====================

    public Map<String, Object> tailor(String jd, String targetJob, String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (jd == null || jd.isBlank()) {
            r.put("success", false);
            r.put("message", "JD 不能为空");
            return r;
        }
        String job = (targetJob == null || targetJob.isBlank()) ? "目标岗位" : targetJob.trim();
        try {
            String profile = buildFactProfile();
            JSONObject content = generateContent(profile, jd, job);
            validateNoFabrication(content, profile);
            if (!content.has("name") || content.optString("name").isBlank()) {
                content.put("name", candidateName());
            }

            String safeName = "定制简历-" + job.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            String fileName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + job.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".docx";
            Path dir = Paths.get(OUTPUT_DIR);
            Files.createDirectories(dir);
            Path out = dir.resolve(fileName);
            writeDocx(out, content);

            ResumeVersionEntity v = new ResumeVersionEntity();
            v.setName((name == null || name.isBlank()) ? safeName : name);
            v.setTargetJob(job);
            v.setJdText(jd.length() > 4000 ? jd.substring(0, 4000) : jd);
            v.setContentJson(content.toString());
            v.setDocxPath(out.toAbsolutePath().toString());
            v.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(v);

            r.put("success", true);
            r.put("versionId", v.getId());
            r.put("name", v.getName());
            r.put("targetJob", job);
            r.put("docxPath", v.getDocxPath());
            r.put("content", content.toMap());
            return r;
        } catch (Exception e) {
            log.error("生成定制简历失败", e);
            r.put("success", false);
            r.put("message", "生成失败: " + e.getMessage());
            return r;
        }
    }

    public Map<String, Object> listVersions() {
        List<ResumeVersionEntity> list = versionMapper.selectList(
                new QueryWrapper<ResumeVersionEntity>().orderByDesc("id"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("total", list.size());
        r.put("versions", list);
        return r;
    }

    /** 从事实库「基本信息」推断姓名，供投递邮件署名使用 */
    public String candidateName() {
        try {
            List<ResumeFactEntity> facts = factMapper.selectList(
                    new QueryWrapper<ResumeFactEntity>().eq("category", "基本信息"));
            if (!facts.isEmpty() && facts.get(0).getContent() != null) {
                String first = facts.get(0).getContent().split("\n")[0].trim();
                if (!first.isEmpty() && first.length() <= 12) return first;
            }
        } catch (Exception ignored) {
        }
        return "个人简历";
    }

    private String buildFactProfile() {
        List<ResumeFactEntity> facts = factMapper.selectList(new QueryWrapper<ResumeFactEntity>().orderByAsc("id"));
        if (facts.isEmpty()) {
            throw new IllegalStateException("事实库为空，请先导入简历");
        }
        StringBuilder sb = new StringBuilder();
        for (ResumeFactEntity f : facts) {
            sb.append("【").append(f.getCategory()).append("】").append(f.getTitle()).append('\n')
                    .append(f.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private JSONObject generateContent(String profile, String jd, String job) {
        String prompt = "你是资深简历优化顾问。下面是候选人的真实经历事实库。你只能重组、改写措辞和调整顺序，"
                + "严禁新增任何事实库里不存在的公司、项目、经历或数字。\n\n"
                + "=== 事实库 ===\n" + profile + "\n"
                + "=== 目标岗位 ===\n" + job + "\n"
                + "=== 岗位 JD ===\n" + jd + "\n\n"
                + "请只输出严格 JSON（不要 markdown 代码块），结构如下：\n"
                + "{\"positioning\":\"一句话定位，40字内\","
                + "\"skills\":[\"技能1\",\"技能2\"],"
                + "\"projects\":[{\"name\":\"项目名（必须来自事实库）\",\"bullets\":[\"要点1\",\"要点2\"]}],"
                + "\"education\":\"教育背景一句话\","
                + "\"summary\":\"30字内自我评价\"}\n"
                + "要求：必须包含上述全部字段；每条 bullet 不超过 60 字，优先突出与 JD 相关的技术点。";

        String resp = aiService.sendRequest(prompt);
        JSONObject obj = parseJson(resp);
        if (obj == null) {
            log.warn("AI 首次输出不是合法 JSON，追加约束重试");
            resp = aiService.sendRequest(prompt + "\n\n注意：上一次输出不是合法 JSON，请只输出 JSON 本身。");
            obj = parseJson(resp);
        }
        if (obj == null) {
            log.warn("AI 输出仍无法解析，使用本地兜底内容");
            obj = fallbackContent(job);
        }
        return obj;
    }

    private JSONObject parseJson(String text) {
        if (text == null) return null;
        String s = text.trim();
        s = s.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONObject(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验：项目名必须能在事实库中找到，否则替换为事实库中的项目名 */
    private void validateNoFabrication(JSONObject content, String profile) {
        JSONArray projects = content.optJSONArray("projects");
        if (projects == null) {
            content.put("projects", new JSONArray());
            return;
        }
        for (int i = 0; i < projects.length(); i++) {
            JSONObject p = projects.getJSONObject(i);
            String name = p.optString("name", "");
            if (!name.isEmpty() && !profile.contains(name)) {
                log.warn("AI 生成了事实库中不存在的项目名，已拦截: {}", name);
                p.put("name", "项目经历");
            }
        }
    }

    private JSONObject fallbackContent(String job) {
        List<ResumeFactEntity> facts = factMapper.selectList(new QueryWrapper<>());
        JSONArray skills = new JSONArray();
        JSONArray projects = new JSONArray();
        String education = "";
        for (ResumeFactEntity f : facts) {
            if ("技能".equals(f.getCategory()) && f.getTags() != null) {
                for (String t : f.getTags().split(",")) if (!t.isBlank()) skills.put(t.trim());
            }
            if ("项目".equals(f.getCategory())) {
                JSONObject p = new JSONObject();
                p.put("name", f.getTitle());
                JSONArray bullets = new JSONArray();
                for (String line : f.getContent().split("\n")) {
                    if (bullets.length() >= 2) break;
                    if (!line.isBlank()) bullets.put(line.length() > 60 ? line.substring(0, 60) : line);
                }
                p.put("bullets", bullets);
                projects.put(p);
            }
            if ("教育".equals(f.getCategory())) {
                education = f.getContent().replace("\n", " ");
                if (education.length() > 60) education = education.substring(0, 60);
            }
        }
        JSONObject o = new JSONObject();
        o.put("positioning", job + "方向候选人，具备完整项目落地经验");
        o.put("skills", skills);
        o.put("projects", projects);
        o.put("education", education);
        o.put("summary", "基础扎实，能快速上手业务开发");
        return o;
    }

    // ==================== docx 导出（纯 Java，无第三方依赖） ====================

    private void writeDocx(Path out, JSONObject c) throws Exception {
        String body = buildDocumentXml(c);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            putEntry(zos, "[Content_Types].xml", CONTENT_TYPES);
            putEntry(zos, "_rels/.rels", RELS);
            putEntry(zos, "word/document.xml", body);
        }
    }

    private void putEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String buildDocumentXml(JSONObject c) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");

        sb.append(para(c.optString("name", "个人简历"), true, 36, "center"));
        if (!c.optString("positioning", "").isEmpty()) {
            sb.append(para(c.optString("positioning"), false, 22, "center"));
        }

        sb.append(heading("专业技能"));
        JSONArray skills = c.optJSONArray("skills");
        if (skills != null) {
            for (int i = 0; i < skills.length(); i++) {
                sb.append(bullet(skills.optString(i)));
            }
        }

        JSONArray projects = c.optJSONArray("projects");
        if (projects != null && projects.length() > 0) {
            sb.append(heading("项目经历"));
            for (int i = 0; i < projects.length(); i++) {
                JSONObject p = projects.getJSONObject(i);
                sb.append(para(p.optString("name"), true, 24, "left"));
                JSONArray bullets = p.optJSONArray("bullets");
                if (bullets != null) {
                    for (int j = 0; j < bullets.length(); j++) {
                        sb.append(bullet(bullets.optString(j)));
                    }
                }
            }
        }

        if (!c.optString("education", "").isEmpty()) {
            sb.append(heading("教育背景"));
            sb.append(para(c.optString("education"), false, 22, "left"));
        }
        if (!c.optString("summary", "").isEmpty()) {
            sb.append(heading("自我评价"));
            sb.append(para(c.optString("summary"), false, 22, "left"));
        }

        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                .append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>");
        sb.append("</w:body></w:document>");
        return sb.toString();
    }

    private String para(String text, boolean bold, int size, String align) {
        String pPr = "center".equals(align) ? "<w:pPr><w:jc w:val=\"center\"/></w:pPr>" : "";
        String rPr = "<w:rPr>" + (bold ? "<w:b/>" : "") + "<w:sz w:val=\"" + size + "\"/></w:rPr>";
        return "<w:p>" + pPr + "<w:r>" + rPr + "<w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r></w:p>";
    }

    private String heading(String text) {
        return para(text, true, 28, "left");
    }

    private String bullet(String text) {
        return para("• " + text, false, 22, "left");
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static final String CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "</Types>";

    private static final String RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";
}

package com.getjobs.worker.fair;

import com.getjobs.application.entity.FairSiteEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地示例数据源：用于离线演示与端到端自测。
 * 数据明确标记来源为 sample，不会与真实站点数据混淆。
 */
@Component
public class SampleFairAdapter implements FairAdapter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public String name() {
        return "sample";
    }

    @Override
    public List<FairItem> fetch(FairSiteEntity site) {
        List<FairItem> list = new ArrayList<>();
        LocalDate today = LocalDate.now();

        list.add(build(site.getSiteCode(), "sample-sust-2027",
                "苏州科技大学 2027 届毕业生秋季双选会", "双选会",
                today.plusDays(7), LocalTime.of(9, 0), LocalTime.of(12, 0),
                "苏州", "苏州科技大学石湖校区体育馆", "苏州科技大学就业指导中心", 86,
                "面向 2027 届毕业生，参会企业以苏州本地制造业、软件与人工智能企业为主。",
                companies(
                        company("苏州同元软控信息技术有限公司", "工业软件", "500-999人",
                                "[{\"title\":\"Java开发工程师\",\"requirement\":\"本科及以上，熟悉Spring Boot、MySQL，了解微服务\",\"salary\":\"10-15K\"}," +
                                        "{\"title\":\"AI应用开发工程师\",\"requirement\":\"熟悉LLM应用、RAG、Agent编排\",\"salary\":\"12-18K\"}]",
                                "https://example.com/apply/tongyuan", "hr@tongyuan.example.com"),
                        company("苏州科达科技股份有限公司", "安防/视频", "1000-4999人",
                                "[{\"title\":\"Java后端开发\",\"requirement\":\"Java基础扎实，熟悉Spring Cloud、Redis\",\"salary\":\"9-14K\"}]",
                                "https://example.com/apply/keda", null),
                        company("苏州工业园区某人工智能科技公司", "人工智能", "100-499人",
                                "[{\"title\":\"AI Agent开发工程师\",\"requirement\":\"熟悉Python/Java，了解Function Calling、向量检索\",\"salary\":\"12-20K\"}]",
                                null, "ai-hr@example.com")
                )));

        list.add(build(site.getSiteCode(), "sample-suda-2027",
                "苏州大学 2027 届毕业生秋季招聘会（IT 专场）", "双选会",
                today.plusDays(12), LocalTime.of(13, 30), LocalTime.of(17, 0),
                "苏州", "苏州大学天赐庄校区", "苏州大学招生就业处", 120,
                "IT/互联网/软件专场，含 Java 后端、算法、测试、产品岗位。",
                companies(
                        company("江苏沙钢集团", "钢铁/制造", "10000人以上",
                                "[{\"title\":\"软件开发工程师\",\"requirement\":\"熟悉Java或C#，了解数据库\",\"salary\":\"8-12K\"}]",
                                "https://example.com/apply/shagang", null),
                        company("苏州敏行医学信息技术有限公司", "医疗信息化", "100-499人",
                                "[{\"title\":\"Java开发工程师\",\"requirement\":\"Spring Boot、MyBatis、MySQL\",\"salary\":\"9-13K\"}]",
                                null, "job@minxing.example.com")
                )));

        list.add(build(site.getSiteCode(), "sample-nju-2027",
                "南京大学 2027 届毕业生大型双选会", "双选会",
                today.plusDays(18), LocalTime.of(9, 0), LocalTime.of(15, 0),
                "南京", "南京大学仙林校区方肇周体育馆", "南京大学学生就业指导中心", 300,
                "长三角地区重点企业专场，含互联网、金融科技、智能制造。",
                companies(
                        company("南京某金融科技公司", "金融科技", "1000-4999人",
                                "[{\"title\":\"Java开发工程师（后端）\",\"requirement\":\"熟悉Spring Boot、分布式、MySQL\",\"salary\":\"13-20K\"}]",
                                "https://example.com/apply/njfin", null)
                )));

        list.add(build(site.getSiteCode(), "sample-online-2027",
                "2027 届高校毕业生秋季网络招聘会（长三角专场）", "线上",
                today.plusDays(3), LocalTime.of(0, 0), LocalTime.of(23, 59),
                "线上", "线上", "国家大学生就业服务平台", 500,
                "线上投递，覆盖长三角地区应届生岗位，可直接在线沟通与投递简历。",
                companies(
                        company("某头部电商平台", "互联网", "10000人以上",
                                "[{\"title\":\"Java后端开发工程师\",\"requirement\":\"本科及以上，Java/Spring/MySQL，2027届\",\"salary\":\"15-25K\"}]",
                                "https://example.com/apply/ecom", null)
                )));

        return list;
    }

    private FairItem build(String siteCode, String key, String title, String type,
                           LocalDate day, LocalTime start, LocalTime end,
                           String city, String location, String host, int companyCount,
                           String summary, List<CompanyItem> companies) {
        FairItem item = new FairItem();
        item.siteCode = siteCode;
        item.eventKey = key;
        item.title = title;
        item.type = type;
        item.startTime = LocalDateTime.of(day, start).format(FMT);
        item.endTime = LocalDateTime.of(day, end).format(FMT);
        item.city = city;
        item.location = location;
        item.host = host;
        item.companyCount = companyCount;
        item.summary = summary;
        item.detailUrl = "";
        item.companies = companies;
        return item;
    }

    private CompanyItem company(String name, String industry, String scale,
                                String jobsJson, String applyUrl, String applyEmail) {
        CompanyItem c = new CompanyItem();
        c.companyName = name;
        c.industry = industry;
        c.scale = scale;
        c.jobsJson = jobsJson;
        c.applyUrl = applyUrl;
        c.applyEmail = applyEmail;
        return c;
    }

    private List<CompanyItem> companies(CompanyItem... items) {
        List<CompanyItem> list = new ArrayList<>();
        for (CompanyItem i : items) list.add(i);
        return list;
    }
}

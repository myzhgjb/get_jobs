package com.getjobs.worker.fair;

import com.getjobs.application.entity.FairSiteEntity;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 国家大学生就业服务平台（24365）招聘会适配器。
 * 直接调用站点的 JSON 接口，无需浏览器。
 */
@Slf4j
@Component
public class NcssFairAdapter implements FairAdapter {

    private static final String LIST_API = "https://www.ncss.cn/student/jobfair/fairList?pageIndex=1&pageSize=30";

    @Override
    public String name() {
        return "ncss";
    }

    @Override
    public List<FairItem> fetch(FairSiteEntity site) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LIST_API))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.ncss.cn/student/jobfair/index.html")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("24365 接口返回 " + resp.statusCode());
        }

        JSONObject root = new JSONObject(resp.body());
        JSONArray list = root.optJSONArray("jobList");
        List<FairItem> result = new ArrayList<>();
        if (list == null) {
            return result;
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            FairItem item = new FairItem();
            item.siteCode = site.getSiteCode();
            item.eventKey = o.optString("fairId", null);
            if (item.eventKey == null || item.eventKey.isEmpty()) {
                continue;
            }
            item.title = o.optString("fairName", "");
            item.type = mapType(o.optString("jobFairType", ""));
            item.startTime = toDateTime(o.optLong("startDate", 0L));
            item.endTime = toDateTime(o.optLong("endDate", 0L));
            item.host = o.optString("sponsor", "");
            item.summary = o.optString("introductions", "");
            item.companyCount = o.optInt("corpNum", 0);
            item.city = guessCity(item.title + " " + item.summary);
            item.location = "线上".equals(item.type) ? "线上" : item.city;
            item.detailUrl = "https://www.ncss.cn/student/jobfair/fairDetail.html?fairId=" + item.eventKey;
            item.rawJson = o.toString();
            result.add(item);
        }
        log.info("24365 抓取到 {} 场招聘会", result.size());
        return result;
    }

    private String mapType(String code) {
        if ("0".equals(code)) return "线上";
        if ("1".equals(code)) return "双选会";
        return "招聘会";
    }

    private String toDateTime(long epochMillis) {
        if (epochMillis <= 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).toString();
    }

    private String guessCity(String text) {
        for (String city : new String[]{"北京", "上海", "天津", "重庆", "广州", "深圳", "苏州", "南京", "杭州",
                "成都", "武汉", "西安", "大连", "合肥", "长沙", "郑州", "济南", "青岛", "厦门", "福州"}) {
            if (text.contains(city)) return city;
        }
        return "";
    }
}

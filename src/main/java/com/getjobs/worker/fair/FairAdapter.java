package com.getjobs.worker.fair;

import com.getjobs.application.entity.FairSiteEntity;

import java.util.List;

/** 招聘会数据源适配器 */
public interface FairAdapter {

    /** 适配器标识，与 fair_site.adapter 对应 */
    String name();

    /** 抓取该站点的招聘会列表 */
    List<FairItem> fetch(FairSiteEntity site) throws Exception;

    /** 统一的招聘会传输对象 */
    class FairItem {
        public String siteCode;
        public String eventKey;
        public String title;
        public String type;
        public String startTime;
        public String endTime;
        public String location;
        public String city;
        public String host;
        public String detailUrl;
        public Integer companyCount = 0;
        public String summary;
        public String rawJson;
        public List<CompanyItem> companies;
    }

    class CompanyItem {
        public String companyName;
        public String industry;
        public String scale;
        public String jobsJson;
        public String applyUrl;
        public String applyEmail;
    }
}

package com.getjobs.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.application.entity.MailOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MailOutboxMapper extends BaseMapper<MailOutboxEntity> {
}

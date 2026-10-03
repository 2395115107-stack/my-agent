package com.myagent.team.mapper;

import com.myagent.team.entity.MailboxMessage;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MailboxMessageMapper extends BaseMapper<MailboxMessage> {
}

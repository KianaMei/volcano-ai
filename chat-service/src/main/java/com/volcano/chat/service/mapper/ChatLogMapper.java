package com.volcano.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.volcano.chat.service.entity.ChatLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatLogMapper extends BaseMapper<ChatLog> {
}

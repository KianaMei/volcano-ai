package com.volcano.chat.service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_logs")
public class ChatLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_uuid")
    private String userUuid;

    private String question;

    private String answer;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

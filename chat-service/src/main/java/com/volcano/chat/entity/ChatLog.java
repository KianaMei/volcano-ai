package com.volcano.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_logs")
public class ChatLog {
    @TableId(value = "record_id", type = IdType.AUTO)
    private Long recordId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private String userId;

    @TableField("user_question")
    private String userQuestion;

    @TableField("ai_answer")
    private String aiAnswer;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("request_time")
    private LocalDateTime requestTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("response_time")
    private LocalDateTime responseTime;

    @TableLogic(value = "0", delval = "1")
    @TableField("deleted")
    private Integer deleted;
}

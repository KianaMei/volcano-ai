CREATE TABLE IF NOT EXISTS chat_logs (
    record_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(255),
    user_id BIGINT NOT NULL,
    user_question TEXT,
    ai_answer TEXT,
    request_time TIMESTAMP NOT NULL,
    response_time TIMESTAMP,
    deleted INTEGER DEFAULT 0
);

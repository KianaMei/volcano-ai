-- PostgreSQL schema for chat_logs table
CREATE TABLE IF NOT EXISTS chat_logs (
    record_id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255),
    user_id VARCHAR(64) NOT NULL,
    user_question TEXT,
    ai_answer TEXT,
    request_time TIMESTAMP NOT NULL,
    response_time TIMESTAMP,
    deleted INTEGER DEFAULT 0
);

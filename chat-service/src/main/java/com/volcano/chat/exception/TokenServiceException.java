package com.volcano.chat.exception;

/**
 * Token 服务异常
 */
public class TokenServiceException extends RuntimeException {
    
    public TokenServiceException(String message) {
        super(message);
    }

    public TokenServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

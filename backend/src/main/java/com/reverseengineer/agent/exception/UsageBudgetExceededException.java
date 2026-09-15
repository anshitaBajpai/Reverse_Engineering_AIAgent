package com.reverseengineer.agent.exception;


public class UsageBudgetExceededException extends RuntimeException {
    public UsageBudgetExceededException(String message) {
        super(message);
    }
}

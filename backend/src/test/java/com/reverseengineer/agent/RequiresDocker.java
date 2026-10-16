package com.reverseengineer.agent;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that needs a Docker daemon (Testcontainers).
 *
 * <p>Locally the class is skipped when Docker is not reachable, so {@code mvn test}
 * still passes on a machine without it. When the {@code CI} environment variable
 * is set (GitHub Actions sets it) the class always runs, so a broken Docker setup
 * fails the build instead of silently skipping the database and Redis tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDocker.Condition.class)
public @interface RequiresDocker {

    class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (System.getenv("CI") != null) {
                return ConditionEvaluationResult.enabled("CI: Docker is required");
            }
            return DockerClientFactory.instance().isDockerAvailable()
                    ? ConditionEvaluationResult.enabled("Docker is available")
                    : ConditionEvaluationResult.disabled("Docker is not available");
        }
    }
}

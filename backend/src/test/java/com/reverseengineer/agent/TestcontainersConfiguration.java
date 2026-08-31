package com.reverseengineer.agent;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spins up a disposable {@code pgvector/pgvector:pg16} container and wires it
 * into the context as the primary datasource. {@code @ServiceConnection} makes
 * Spring Boot publish the container's JDBC URL/credentials so Flyway, the
 * PgVector store, and {@code JdbcTemplate} all target it with no extra config.
 *
 * <p>Import this from any {@code @SpringBootTest} that needs a live database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> pgVectorContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                        .asCompatibleSubstituteFor("postgres"));
    }
}

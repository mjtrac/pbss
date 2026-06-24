/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("gov.election.viewer.entity")
@EnableJpaRepositories("gov.election.viewer.repository")
public class BallotViewerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BallotViewerApplication.class, args);
    }
}

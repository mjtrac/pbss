/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Election Ballot Management System
 *
 * Entry point for the Spring Boot application.
 * Run with: java -jar bBuilder-1.0.0.jar
 *
 * Profiles:
 *   -Dspring.profiles.active=sqlite   (default, file-based)
 *   -Dspring.profiles.active=postgres (requires DB_USER / DB_PASS env vars)
 *
 * Data directories (created automatically on first run):
 *   ~/bBuilder_data/db/               — SQLite database
 *   ~/bBuilder_data/ballot_templates/ — Generated ballot PDFs and YAMLs
 */
@SpringBootApplication
public class ElectionBallotApplication {
    public static void main(String[] args) {
        // Create all required data directories before Spring initialises.
        // The DB directory must exist before Hibernate tries to open the SQLite file.
        String home = System.getProperty("user.home");
        String dataDir = home + "/pbss_data";
        String[] dirs = {
            dataDir,
            dataDir + "/db",
            dataDir + "/ballot_templates",
            dataDir + "/logs",
        };
        for (String dir : dirs) {
            try {
                Files.createDirectories(Paths.get(dir));
            } catch (Exception e) {
                System.err.println("Warning: could not create directory " + dir
                    + ": " + e.getMessage());
            }
        }
        SpringApplication.run(ElectionBallotApplication.class, args);
    }
}

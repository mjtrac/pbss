/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package com.mjtrac.counter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication(scanBasePackages = {"com.mjtrac.counter", "com.mjtrac.viewer"})
@EntityScan("com.mjtrac.counter.entity")
@EnableJpaRepositories({"com.mjtrac.counter.repository"})
public class ElectionCounterApplication {
    public static void main(String[] args) {
        // Create all required data directories before Spring initialises.
        // DB directory must exist before Hibernate opens the SQLite file.
        String home = System.getProperty("user.home");
        String dataDir = home + "/pbss_data";
        String[] dirs = {
            dataDir,
            dataDir + "/db",
            dataDir + "/cast_ballot_scans",
            dataDir + "/reports",
            dataDir + "/writeins",
            dataDir + "/scribbles",
            dataDir + "/logs",
        };
        for (String dir : dirs) {
            try {
                Files.createDirectories(Paths.get(dir));
            } catch (Exception e) {
                System.err.println("Warning: could not create directory "
                    + dir + ": " + e.getMessage());
            }
        }
        SpringApplication.run(ElectionCounterApplication.class, args);
    }
}

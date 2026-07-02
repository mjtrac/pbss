/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.*;

@SpringBootApplication(scanBasePackages = {"gov.election.counter", "gov.election.viewer"})
@EntityScan("gov.election.counter.entity")
@EnableJpaRepositories({"gov.election.counter.repository"})
public class ElectionCounterApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ElectionCounterApplication.class);
        // Create data directories before the datasource bean is initialized.
        // SQLite cannot create its parent directory automatically, so we must
        // ensure ~/bSuite_data/db (and siblings) exist before JPA starts.
        app.addInitializers(new DataDirectoryInitializer());
        app.run(args);
    }

    /**
     * Creates all data subdirectories derived from data.dir before any beans
     * (including the JPA datasource) are instantiated.
     */
    static class DataDirectoryInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ConfigurableEnvironment env = context.getEnvironment();
            String[] dirProperties = {
                "data.database.dir",
                "data.reports.dir",
                "data.writeins.dir",
                "data.scribbles.dir",
                "data.ballots.dir",
            };
            for (String prop : dirProperties) {
                String dir = env.getProperty(prop);
                if (dir != null && !dir.isBlank()) {
                    try {
                        Files.createDirectories(Paths.get(dir));
                    } catch (Exception e) {
                        System.err.println("[bCounter] Warning: could not create "
                            + prop + "=" + dir + ": " + e.getMessage());
                    }
                }
            }
        }
    }
}

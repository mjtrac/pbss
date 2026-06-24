/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 * See <https://www.gnu.org/licenses/> for the full license text.
 */
package gov.election.counter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"gov.election.counter", "gov.election.viewer"})
@EntityScan("gov.election.counter.entity")
@EnableJpaRepositories({"gov.election.counter.repository"})
public class ElectionCounterApplication {
    public static void main(String[] args) {
        SpringApplication.run(ElectionCounterApplication.class, args);
    }
}

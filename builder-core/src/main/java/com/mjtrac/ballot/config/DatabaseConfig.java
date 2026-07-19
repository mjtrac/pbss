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
package com.mjtrac.ballot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA/database configuration.
 * Profile-specific datasource properties live in:
 *   application-sqlite.properties   (default)
 *   application-postgres.properties (set -Dspring.profiles.active=postgres)
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.mjtrac.ballot.repository")
@EnableTransactionManagement
public class DatabaseConfig {
    // Spring Boot auto-configures DataSource from the active profile's properties.
}

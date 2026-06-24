/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package gov.election.viewer.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a second HTTP connector on viewer.server.port (default 8082) so
 * bCounter (port 8081) and bViewer (port 8082) run in a single process.
 *
 * Security is separated by port in ViewerSecurityConfig and CounterSecurityConfig.
 */
@Configuration
public class ViewerServerConfig {

    @Value("${viewer.server.port:8082}")
    private int viewerPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> viewerConnector() {
        return factory -> {
            Connector connector = new Connector(
                TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(viewerPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}

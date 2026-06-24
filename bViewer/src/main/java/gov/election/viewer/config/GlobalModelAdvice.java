/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Ballot System — licensed under the GNU General Public License v3.
 */
package gov.election.viewer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds global model attributes available in every Thymeleaf template. */
@ControllerAdvice
public class GlobalModelAdvice {

    @Value("${app.background-color:}")
    private String backgroundColor;

    @ModelAttribute("appBgColor")
    public String appBgColor() {
        return backgroundColor;
    }
}

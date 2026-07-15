/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Ballot System — licensed under the GNU General Public License v3.
 */
package com.mjtrac.ballot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds global model attributes available in every Thymeleaf template.
 * app.background-color: optional CSS color for the page background.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    @Value("${app.background-color:}")
    private String backgroundColor;

    @Value("${app.login-title:pbss Ballot Builder}")
    private String loginTitle;

    @Value("${app.login-logo:}")
    private String loginLogo;

    @ModelAttribute("appBgColor")
    public String appBgColor() { return backgroundColor; }

    @ModelAttribute("loginTitle")
    public String loginTitle() { return loginTitle; }

    @ModelAttribute("loginLogo")
    public String loginLogo() { return loginLogo; }
}

/*
 * Copyright (C) 2026 Mitch Trachtenberg
 * Election Counter — licensed under the GNU General Public License v3.
 */
package com.mjtrac.counter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds global model attributes available in every Thymeleaf template. */
@ControllerAdvice
public class GlobalModelAdvice {

    @Value("${app.background-color:}")
    private String backgroundColor;

    @Value("${app.login-title:pbss Ballot Counter}")
    private String loginTitle;

    @Value("${app.login-logo:}")
    private String loginLogo;

    @Value("${app.viewer-login-title:pbss Ballot Viewer}")
    private String viewerLoginTitle;

    @Value("${app.viewer-login-logo:}")
    private String viewerLoginLogo;

    @ModelAttribute("appBgColor")
    public String appBgColor() { return backgroundColor; }

    @ModelAttribute("loginTitle")
    public String loginTitle() { return loginTitle; }

    @ModelAttribute("loginLogo")
    public String loginLogo() { return loginLogo; }

    @ModelAttribute("viewerLoginTitle")
    public String viewerLoginTitle() { return viewerLoginTitle; }

    @ModelAttribute("viewerLoginLogo")
    public String viewerLoginLogo() { return viewerLoginLogo; }
}

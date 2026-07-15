/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 *
 * PageReachabilityTest — verifies every primary bBuilder URL returns 200
 * for an authenticated admin user. Fails immediately if any controller
 * method is removed or a security matcher misconfigured.
 */
package com.mjtrac.ballot;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sqlite")
@DisplayName("Page reachability — all primary URLs return 200")
class PageReachabilityTest {

    @Autowired MockMvc mvc;

    @org.springframework.beans.factory.annotation.Value(
        "${test.admin.password:ChangeMe123!}")
    private String adminPassword;

    private static MockHttpSession adminSession;

    @BeforeEach
    void ensureLoggedIn() throws Exception {
        if (adminSession != null) return;
        MvcResult login = mvc.perform(post("/login")
                .with(csrf())
                .param("username", "admin")
                .param("password", adminPassword))
            .andReturn();
        // Follow redirect
        String location = login.getResponse().getRedirectedUrl();
        MockHttpSession s = (MockHttpSession) login.getRequest().getSession(false);
        if (location != null && s != null) {
            mvc.perform(get(location).session(s)).andReturn();
        }
        adminSession = s;
        assertThat(adminSession).as("Admin session must be created after login").isNotNull();
    }

    @ParameterizedTest(name = "GET {0} → 200")
    @ValueSource(strings = {
        "/dashboard",
        "/data/elections",
        "/data/regions",
        "/data/parties",
        "/data/ballot-types",
        "/data/contests",
        "/data/languages",
        "/data/ballot-templates",
        "/data/ballot-combinations",
        "/print",
        "/admin",
    })
    @DisplayName("Authenticated GET returns 200")
    void pageReturns200(String url) throws Exception {
        mvc.perform(get(url).session(adminSession))
            .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "GET {0} → 302 redirect to login")
    @ValueSource(strings = {
        "/dashboard",
        "/data/elections",
        "/data/contests",
        "/print",
        "/admin",
    })
    @DisplayName("Unauthenticated GET redirects to login")
    void unauthenticatedRedirectsToLogin(String url) throws Exception {
        mvc.perform(get(url))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location",
                org.hamcrest.Matchers.containsString("/login")));
    }

    @Test
    @DisplayName("GET /api/test/ping returns 200 with status=ok")
    void testApiPing() throws Exception {
        mvc.perform(get("/api/test/ping").session(adminSession))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString("ok")));
    }
}

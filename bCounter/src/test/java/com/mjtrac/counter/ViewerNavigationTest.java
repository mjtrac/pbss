/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.counter;

import com.mjtrac.counter.entity.CounterUser;
import com.mjtrac.counter.repository.CounterUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests that a viewer-authenticated user on port 8082 can navigate
 * between the ballot image list and the full /results page (same
 * controller/path as port 8081 — VIEWER is explicitly permitted on
 * /results, /rcv-report, /scribble-report, /scribble-image) without
 * requiring a separate bCounter login on port 8081.
 *
 * Regression guard: if VIEWER's access to /results is ever narrowed back
 * to ADMIN/COUNTER_OPERATOR only in CounterSecurityConfig, these tests fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sqlite")
@DisplayName("Viewer navigation — ballot list ↔ results report")
class ViewerNavigationTest {

    @Autowired MockMvc mvc;
    @Autowired CounterUserRepository userRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String VIEWER_USERNAME = "vnav_test_viewer";
    private static final String VIEWER_PASSWORD = "TestViewer#2026!";

    private MockHttpSession viewerSession;

    @BeforeEach
    void loginAsViewer() throws Exception {
        // Dedicated VIEWER-role test user — independent of the seeded ADMIN
        // account, so this test exercises the actual VIEWER role gate.
        if (userRepo.findByUsername(VIEWER_USERNAME).isEmpty()) {
            CounterUser u = new CounterUser();
            u.setUsername(VIEWER_USERNAME);
            u.setPasswordHash(passwordEncoder.encode(VIEWER_PASSWORD));
            u.setRoles(Set.of(CounterUser.Role.VIEWER));
            u.setEnabled(true);
            userRepo.save(u);
        }

        // Perform login — expect 302 redirect to /viewer/
        MvcResult login = mvc.perform(post("/viewer/login")
                .with(csrf())
                .param("username", VIEWER_USERNAME)
                .param("password", VIEWER_PASSWORD))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/viewer/")))
            .andReturn();

        // The authenticated session is on the request that was processed
        viewerSession = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(viewerSession).as("Viewer session must exist after login").isNotNull();

        // Follow the redirect to confirm authentication is complete
        mvc.perform(get(login.getResponse().getRedirectedUrl())
                .session(viewerSession))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /viewer/ returns 200 for authenticated viewer user")
    void testViewerIndexAccessible() throws Exception {
        mvc.perform(get("/viewer/").session(viewerSession))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Ballot Viewer")));
    }

    @Test
    @DisplayName("GET /results returns 200 for a VIEWER-role user")
    void testViewerReportAccessible() throws Exception {
        mvc.perform(get("/results").session(viewerSession))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Results")));
    }

    @Test
    @DisplayName("GET /results contains a link back to the ballot viewer")
    void testReportHasBackLink() throws Exception {
        String body = mvc.perform(get("/results").session(viewerSession))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("/viewer/");
    }

    @Test
    @DisplayName("GET /viewer/ contains a link to /results, not a hardcoded localhost:8081")
    void testIndexHasResultsLink() throws Exception {
        String body = mvc.perform(get("/viewer/").session(viewerSession))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body)
            .contains("/results")
            .doesNotContain("localhost:8081/results");
    }

    @Test
    @DisplayName("GET /results arriving on the viewer port redirects (via /login) to /viewer/login for an unauthenticated user")
    void testReportRequiresAuth() throws Exception {
        // A real browser hitting :8082/results unauthenticated gets two hops:
        // Spring Security's formLogin redirects to the configured loginPage
        // ("/login"), then LoginController's own port check re-redirects
        // /login -> /viewer/login when request.getLocalPort() == viewerPort.
        // MockMvc doesn't follow redirects automatically, so simulate both
        // requests explicitly with the same simulated local port.
        var setViewerPort = (org.springframework.test.web.servlet.request.RequestPostProcessor)
            request -> { request.setLocalPort(8082); return request; };

        mvc.perform(get("/results").with(setViewerPort))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/login")));

        mvc.perform(get("/login").with(setViewerPort))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", containsString("/viewer/login")));
    }

    @Test
    @DisplayName("Navigation round-trip: index → results → index all return 200")
    void testRoundTrip() throws Exception {
        mvc.perform(get("/viewer/").session(viewerSession))
            .andExpect(status().isOk());
        mvc.perform(get("/results").session(viewerSession))
            .andExpect(status().isOk());
        mvc.perform(get("/viewer/").session(viewerSession))
            .andExpect(status().isOk());
    }
}

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
package gov.election.ballot;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sqlite")
class SecurityTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("Unauthenticated access to /print redirects to login")
    void unauthenticatedPrint() throws Exception {
        mvc.perform(get("/print"))
           .andExpect(status().is3xxRedirection())
           .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("DATA_ENTRY role cannot access /print")
    void dataEntryCannotPrint() throws Exception {
        mvc.perform(get("/print").with(user("entry").roles("DATA_ENTRY")))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PRINTER role can access /print form")
    void printerCanAccessPrint() throws Exception {
        mvc.perform(get("/print").with(user("printer").roles("PRINTER")))
           .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRINTER role cannot access /admin")
    void printerCannotAdmin() throws Exception {
        mvc.perform(get("/admin").with(user("printer").roles("PRINTER")))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN role can access /admin")
    void adminCanAccessAdmin() throws Exception {
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
           .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Login page is accessible without authentication")
    void loginPageAccessible() throws Exception {
        mvc.perform(get("/login"))
           .andExpect(status().isOk());
    }
}

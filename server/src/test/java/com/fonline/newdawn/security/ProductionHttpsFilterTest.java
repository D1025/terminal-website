package com.fonline.newdawn.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionHttpsFilterTest {
    private final ProductionHttpsFilter filter = new ProductionHttpsFilter();

    @Test
    void rejectsInsecureApiTraffic() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setSecure(false);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(426);
        assertThat(response.getContentAsString()).contains("HTTPS_REQUIRED");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void allowsSecureApiTraffic() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setSecure(true);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}

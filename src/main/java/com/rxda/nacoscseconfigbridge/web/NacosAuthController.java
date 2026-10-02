package com.rxda.nacoscseconfigbridge.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the Nacos login endpoint that authenticated clients call first.
 *
 * <p>A Nacos client configured with {@code username}/{@code password} posts to
 * {@code /nacos/v1/auth/users/login} before it reads anything. Without this
 * endpoint the bridge answered HTTP 404, so every such client logged
 * {@code login failed} and kept retrying the login on its token refresh
 * schedule.</p>
 *
 * <p>The bridge has no user store: it issues an opaque access token and does
 * not verify the submitted credentials. That does not weaken the bridge,
 * because reading configuration through it was never authenticated anyway;
 * deployments that need authentication have to enforce it in front of the
 * bridge. The credentials themselves are never logged.</p>
 */
@RestController
public class NacosAuthController {

    /** Token lifetime in seconds, the same default the Nacos server uses. */
    public static final long TOKEN_TTL_SECONDS = 18000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosAuthController.class);

    /** Ensures the "credentials are not verified" warning is logged once. */
    private final AtomicBoolean announced = new AtomicBoolean();

    /**
     * Issues an access token for one client login request.
     *
     * <p>The paths cover the client defaults: the Nacos client appends
     * {@code /v1/auth/users/login} to its context path ({@code /nacos}), and
     * other tooling uses {@code /v1/auth/login} with or without that prefix.</p>
     *
     * @param username submitted username, not verified
     * @param password submitted password, not verified
     * @return access token payload expected by Nacos clients
     */
    @PostMapping(path = {
            "/nacos/v1/auth/users/login",
            "/v1/auth/users/login",
            "/nacos/v1/auth/login",
            "/v1/auth/login"
        }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> login(
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "password", required = false) String password) {
        if (announced.compareAndSet(false, true)) {
            LOGGER.warn("Nacos client login endpoint called; this read-only bridge does not verify "
                    + "credentials and issues access tokens only to keep authenticated clients working");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", UUID.randomUUID().toString());
        // Nacos clients read tokenTtl as a number of seconds and stop working
        // when the field is missing, so it is always present.
        body.put("tokenTtl", TOKEN_TTL_SECONDS);
        body.put("globalAdmin", false);
        body.put("username", username == null ? "" : username);
        return ResponseEntity.ok(body);
    }
}

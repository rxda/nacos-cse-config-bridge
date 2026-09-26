package com.rxda.nacoscseconfigbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature switch for the Nacos-to-KIE migration endpoint.
 */
@ConfigurationProperties(prefix = "srv-nacos-cse-config-bridge.migration")
public class MigrationProperties {

    /** Migration is disabled by default because the endpoint writes to CSE. */
    private boolean enabled;

    /**
     * Returns whether migration requests are accepted.
     *
     * @return {@code true} when migration is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the migration endpoint.
     *
     * @param enabled whether migration is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

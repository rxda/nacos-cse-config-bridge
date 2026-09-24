package com.rxda.nacoscseconfigbridge.migration;

import java.util.List;

public record NacosMigrationRequest(
        String sourceServerAddr,
        String sourceNamespace,
        String sourceAccessToken,
        boolean overwrite,
        boolean allNamespaces,
        List<ConfigItem> configs) {

    public record ConfigItem(String dataId, String group, String tenant) {
    }
}

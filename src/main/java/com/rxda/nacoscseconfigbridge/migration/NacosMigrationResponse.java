package com.rxda.nacoscseconfigbridge.migration;

import java.util.List;

public record NacosMigrationResponse(
        int total,
        int success,
        int skipped,
        int failed,
        List<ItemResult> items) {

    public record ItemResult(String dataId, String group, String tenant, String status, String message) {
    }
}

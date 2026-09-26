package com.rxda.nacoscseconfigbridge.migration;

import java.util.List;

/**
 * Summary returned after a migration run.
 *
 * @param total number of processed items
 * @param success number of successfully migrated items
 * @param skipped number of items skipped by policy
 * @param failed number of failed items
 * @param items per-item outcomes
 */
public record NacosMigrationResponse(
        int total,
        int success,
        int skipped,
        int failed,
        List<ItemResult> items) {

    /** Details of one migrated configuration outcome. */
    public record ItemResult(String dataId, String group, String tenant, String status, String message) {
    }
}

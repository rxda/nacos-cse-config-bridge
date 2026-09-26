package com.rxda.nacoscseconfigbridge.migration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.config.MigrationProperties;

/**
 * HTTP entry point for the optional Nacos-to-KIE migration operation.
 */
@RestController
@RequestMapping("/srv-nacos-cse-config-bridge/v1/migration")
public class NacosMigrationController {

    private final MigrationProperties properties;
    private final NacosMigrationService migrationService;
    /**
     * Creates the migration HTTP controller.
     *
     * @param properties migration feature switch
     * @param migrationService migration service
     */
    public NacosMigrationController(MigrationProperties properties, NacosMigrationService migrationService) {
        this.properties = properties;
        this.migrationService = migrationService;
    }
    /**
     * Migrates explicitly selected or discovered Nacos configurations.
     *
     * @param request migration source and selection options
     * @return migration summary, or 404 when migration is disabled
     */
    @PostMapping("/configs")
    public ResponseEntity<NacosMigrationResponse> migrate(@RequestBody NacosMigrationRequest request) {
        if (!properties.isEnabled()) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(migrationService.migrate(request));
    }
}

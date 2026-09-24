package com.rxda.nacoscseconfigbridge.migration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.config.MigrationProperties;

@RestController
@RequestMapping("/srv-nacos-cse-config-bridge/v1/migration")
public class NacosMigrationController {

    private final MigrationProperties properties;
    private final NacosMigrationService migrationService;

    public NacosMigrationController(MigrationProperties properties, NacosMigrationService migrationService) {
        this.properties = properties;
        this.migrationService = migrationService;
    }

    @PostMapping("/configs")
    public ResponseEntity<NacosMigrationResponse> migrate(@RequestBody NacosMigrationRequest request) {
        if (!properties.isEnabled()) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(migrationService.migrate(request));
    }
}

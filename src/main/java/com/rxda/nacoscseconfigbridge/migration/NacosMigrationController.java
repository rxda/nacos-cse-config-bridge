package com.rxda.nacoscseconfigbridge.migration;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rxda.nacoscseconfigbridge.config.MigrationProperties;

/**
 * 可选的 Nacos 到 KIE 迁移操作的 HTTP 入口。
 */
@RestController
@RequestMapping("/srv-nacos-cse-config-bridge/v1/migration")
@RequiredArgsConstructor
public class NacosMigrationController {

    private final MigrationProperties properties;
    private final NacosMigrationService migrationService;

    /**
     * 迁移明确选定或发现到的 Nacos 配置。
     *
     * @param request 迁移源和选择选项
     * @return 迁移汇总；迁移禁用时返回 404
     */
    @PostMapping("/configs")
    public ResponseEntity<NacosMigrationResponse> migrate(@RequestBody NacosMigrationRequest request) {
        if (!properties.isEnabled()) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(migrationService.migrate(request));
    }
}

package com.rxda.nacoscseconfigbridge.migration;

import java.util.List;

/**
 * 迁移任务结束后返回的汇总。
 *
 * @param total 处理的条目数
 * @param success 成功迁移的条目数
 * @param skipped 按策略跳过的条目数
 * @param failed 失败的条目数
 * @param items 逐条结果
 */
public record NacosMigrationResponse(
        int total,
        int success,
        int skipped,
        int failed,
        List<ItemResult> items) {

    /** 单个配置迁移结果的详情。 */
    public record ItemResult(String dataId, String group, String tenant, String status, String message) {
    }
}

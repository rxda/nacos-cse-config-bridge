package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析和序列化 Nacos HTTP 监听载荷。
 */

@Component
public class NacosListenerParser {

    private static final String RECORD_SEPARATOR = "\u0001";
    private static final String FIELD_SEPARATOR = "\u0002";

    /**
     * 解析 Nacos 记录/字段分隔的监听格式。
     *
     * @param value 原始请求体或请求头值
     * @return 解析后的监听条目
     */
    public List<NacosListenerEntry> parse(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(RECORD_SEPARATOR))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::parseRecord)
                .collect(Collectors.toList());
    }

    /**
     * 解析 Nacos 监听载荷中的一条记录。
     *
     * @param record 包含 dataId、group、tenant 和 MD5 的记录
     * @return 解析后的监听条目
     */
    private NacosListenerEntry parseRecord(String record) {
        String[] fields = record.split(FIELD_SEPARATOR, -1);
        if (fields.length < 4) {
            throw new IllegalArgumentException("Invalid Nacos Listening-Configs record");
        }
        NacosConfigKey key = new NacosConfigKey(fields[0], fields[1], fields[2]);
        return new NacosListenerEntry(key, fields[3]);
    }

    /**
     * 把变更的键序列化为 Nacos 监听响应语法。
     *
     * @param changed 内容变更的键
     * @return Nacos 响应载荷
     */
    public String response(List<NacosConfigKey> changed) {
        return changed.stream()
                .map(key -> key.dataId() + FIELD_SEPARATOR + key.effectiveGroup() + FIELD_SEPARATOR
                        + (key.tenant() == null ? "" : key.tenant()))
                .collect(Collectors.joining(RECORD_SEPARATOR));
    }
}

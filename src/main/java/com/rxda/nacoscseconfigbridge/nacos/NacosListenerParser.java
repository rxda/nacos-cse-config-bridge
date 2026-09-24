package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NacosListenerParser {

    private static final String RECORD_SEPARATOR = "\u0001";
    private static final String FIELD_SEPARATOR = "\u0002";

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

    private NacosListenerEntry parseRecord(String record) {
        String[] fields = record.split(FIELD_SEPARATOR, -1);
        if (fields.length < 4) {
            throw new IllegalArgumentException("Invalid Nacos Listening-Configs record");
        }
        NacosConfigKey key = new NacosConfigKey(fields[0], fields[1], fields[2]);
        return new NacosListenerEntry(key, fields[3]);
    }

    public String response(List<NacosConfigKey> changed) {
        return changed.stream()
                .map(key -> key.dataId() + FIELD_SEPARATOR + key.effectiveGroup() + FIELD_SEPARATOR
                        + (key.tenant() == null ? "" : key.tenant()))
                .collect(Collectors.joining(RECORD_SEPARATOR));
    }
}

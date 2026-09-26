package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Locale;

/**
 * Maps a Nacos config type (or, when metadata is unavailable, a dataId suffix)
 * to a value type supported by the CSE KIE console.
 *
 * <p>The bridge reads the raw KIE {@code value}, so typed storage does not
 * change the original document formatting returned to Nacos clients.</p>
 */
public final class NacosConfigFormat {
    /** Prevents instantiation of this format-mapping utility. */
    private NacosConfigFormat() {
    }

    /**
     * Uses the type stored by Nacos and falls back to the conventional suffix
     * only when the source request does not carry type metadata.
     */
    public static String fromNacosType(String nacosType, String dataId) {
        if (nacosType != null && !nacosType.isBlank()) {
            return switch (nacosType.trim().toLowerCase(Locale.ROOT)) {
                case "yaml", "yml" -> "yaml";
                case "properties", "eproperties" -> "properties";
                case "ini", "json", "xml", "text", "string" -> nacosType.trim().toLowerCase(Locale.ROOT);
                // KIE has no toml/html/etc. type; do not reinterpret a stored
                // Nacos type from an unrelated dataId suffix.
                default -> "text";
            };
        }
        return fromDataId(dataId);
    }

    /**
     * Compatibility fallback for callers that only have a Nacos dataId.
     */
    public static String fromDataId(String dataId) {
        if (dataId == null) {
            return "text";
        }
        String normalized = dataId.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return "yaml";
        }
        if (normalized.endsWith(".properties") || normalized.endsWith(".eproperties")) {
            return "properties";
        }
        if (normalized.endsWith(".ini")) {
            return "ini";
        }
        if (normalized.endsWith(".json")) {
            return "json";
        }
        if (normalized.endsWith(".xml")) {
            return "xml";
        }
        return "text";
    }
}

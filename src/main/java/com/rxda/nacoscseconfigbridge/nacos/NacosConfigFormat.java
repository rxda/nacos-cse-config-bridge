package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Locale;

/**
 * 把 Nacos 配置类型（元数据缺失时用 dataId 后缀代替）
 * 映射为 CSE KIE 控制台支持的值类型。
 *
 * <p>网桥读取的是 KIE 的原始 {@code value}，所以类型化存储不会改变
 * 返回给 Nacos 客户端的原始文档格式。</p>
 */
public final class NacosConfigFormat {
    /** 阻止实例化这个格式映射工具类。 */
    private NacosConfigFormat() {
    }

    /**
     * 使用 Nacos 存储的类型；只有源请求不携带类型元数据时，
     * 才回退到按 dataId 后缀推断。
     */
    public static String fromNacosType(String nacosType, String dataId) {
        if (nacosType != null && !nacosType.isBlank()) {
            return switch (nacosType.trim().toLowerCase(Locale.ROOT)) {
                case "yaml", "yml" -> "yaml";
                case "properties", "eproperties" -> "properties";
                case "ini", "json", "xml", "text", "string" -> nacosType.trim().toLowerCase(Locale.ROOT);
                // KIE 没有 toml/html 等类型；不能用无关的 dataId 后缀
                // 去重新解释一个已存储的 Nacos 类型。
                default -> "text";
            };
        }
        return fromDataId(dataId);
    }

    /**
     * 兼容性回退：调用方只有 Nacos dataId 时使用。
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

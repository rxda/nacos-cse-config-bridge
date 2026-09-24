package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Map;

/** Boundary between the Nacos protocol endpoint and optional CSE registration. */
public interface NacosClientRegistration {

    Registration register(String tenant, Map<String, String> labels, String remoteHost);

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    NacosClientRegistration NOOP = (tenant, labels, remoteHost) -> () -> {
    };
}

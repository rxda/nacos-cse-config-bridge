package com.rxda.nacoscseconfigbridge.nacos;

import java.util.Map;

/**
 * Registers Nacos protocol clients as optional CSE Service Center observers.
 */
public interface NacosClientRegistration {

    /**
     * Registers one Nacos client connection.
     *
     * @param tenant Nacos tenant from the connection setup
     * @param labels client connection labels
     * @param remoteHost peer address observed by the bridge
     * @return handle that unregisters the client when closed
     */
    Registration register(String tenant, Map<String, String> labels, String remoteHost);

    /** Handle for releasing a client registration. */
    interface Registration extends AutoCloseable {
        /**
         * Releases the Service Center registration represented by this handle.
         */
        @Override
        void close();
    }

    NacosClientRegistration NOOP = (tenant, labels, remoteHost) -> () -> {
    };
}

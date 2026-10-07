package com.ticketflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ticketflow.async")
public record AsyncProperties(@DefaultValue("false") boolean enabled,@DefaultValue("true") boolean jobsEnabled,
        @DefaultValue("127.0.0.1") String host,@DefaultValue("15673") int port,
        @DefaultValue("tf_app") String username,@DefaultValue("") String password,
        @DefaultValue("/ticketflow-dev") String vhost,@DefaultValue("tf.dev.async") String brokerPrefix,
        @DefaultValue("30") int queryLimit,@DefaultValue("16") int queryConcurrency) {
    public AsyncProperties {
        if(port<1 || port>65535 || queryLimit<1 || queryLimit>1000 || queryConcurrency<1 || queryConcurrency>100 || !brokerPrefix.matches("tf\\.(dev|test|demo)\\.[A-Za-z0-9_.-]{1,80}"))
            throw new IllegalArgumentException("Invalid async settings");
    }
}

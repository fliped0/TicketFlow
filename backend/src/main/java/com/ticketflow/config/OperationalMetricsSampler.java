package com.ticketflow.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in local sampling; no HTTP management endpoint or SQL is added. */
@Component
@ConditionalOnProperty(name="ticketflow.observability.enabled",havingValue="true")
public class OperationalMetricsSampler {
    private final MeterRegistry registry;
    private final JsonMapper json;
    public OperationalMetricsSampler(MeterRegistry registry, JsonMapper json) { this.registry=registry; this.json=json; }
    private static final Set<String> TAGS=Set.of("pool","area","id","action","cause","method","uri","status","outcome","exception");
    public Map<String,Object> snapshot() {
        var meters=new ArrayList<Map<String,Object>>();
        for (Meter meter:registry.getMeters()) {
            String name=meter.getId().getName();
            if (!(name.startsWith("hikaricp.") || name.startsWith("jvm.") || name.startsWith("process.")
                    || name.equals("system.cpu.usage") || name.equals("http.server.requests") || name.startsWith("ticketflow."))) continue;
            var tags=new TreeMap<String,String>();
            for (var tag:meter.getId().getTags()) if (TAGS.contains(tag.getKey())) tags.put(tag.getKey(),tag.getValue());
            var values=new TreeMap<String,Double>();
            for (var measurement:meter.measure()) {
                double value=measurement.getValue();
                if (Double.isFinite(value)) values.put(measurement.getStatistic().name(),value);
            }
            meters.add(Map.of("name",name,"tags",tags,"values",values));
        }
        return Map.of("observedAt",Instant.now().toString(),"meters",meters);
    }
    @Scheduled(fixedDelayString="${ticketflow.observability.interval-ms:5000}",initialDelayString="${ticketflow.observability.interval-ms:5000}")
    public void sample() { LoggerFactory.getLogger(getClass()).info("metrics_snapshot {}",json.writeValueAsString(snapshot())); }
}

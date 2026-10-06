package com.ticketflow.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OperationalMetricsSamplerTest {
    @Test void samplerKeepsFiniteOperationalMeasuresAndExcludesUnrelatedMetersAndTags() {
        var registry=new SimpleMeterRegistry();
        registry.counter("hikaricp.test","pool","test","secret","must-not-log").increment(2);
        registry.counter("business.private","userId","123").increment();
        registry.gauge("process.nan",Double.NaN);
        var result=new OperationalMetricsSampler(registry,JsonMapper.builder().build()).snapshot();
        var text=JsonMapper.builder().build().writeValueAsString(result);
        assertTrue(text.contains("hikaricp.test"));assertTrue(text.contains("COUNT"));
        assertFalse(text.contains("must-not-log"));assertFalse(text.contains("business.private"));assertFalse(text.contains("NaN"));
        assertNotNull(result.get("observedAt"));
    }
}

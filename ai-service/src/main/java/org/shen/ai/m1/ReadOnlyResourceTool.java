package org.shen.ai.m1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class ReadOnlyResourceTool {
    private final BookingClient booking;
    private final List<Map<String, Object>> verified = new ArrayList<>();
    private final List<String> traces = new ArrayList<>();

    public ReadOnlyResourceTool(BookingClient booking) {
        this.booking = booking;
    }

    @Tool(name = "getResourceDetails", description = "Read only. Fetch the DEMO resource demo-venue-1. UNKNOWN does not mean available. No bookings or writes.")
    public Map<String, Object> getResourceDetails(
            @ToolParam(description = "Only demo-venue-1 is allowed in this M1 probe") String resourceId) {
        if (!"demo-venue-1".equals(resourceId) || verified.size() >= 2) {
            throw new IllegalArgumentException("Invalid resource or tool call limit exceeded");
        }
        var response = booking.resource(resourceId);
        if (!"OK".equals(response.code()) || response.data() == null
                || !resourceId.equals(response.data().get("resourceId"))
                || !"DEMO".equals(response.data().get("dataMode"))) {
            throw new IllegalStateException("Unverified resource result");
        }
        verified.add(response.data());
        traces.add(response.traceId());
        return response.data();
    }

    public List<Map<String, Object>> verifiedResources() {
        return List.copyOf(verified);
    }

    public List<String> downstreamTraces() {
        return List.copyOf(traces);
    }
}

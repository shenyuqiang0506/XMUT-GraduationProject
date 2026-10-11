package org.shen.ai.m1;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("m1 & ai")
class M1AiController {
    record Query(@NotBlank @Size(max = 512) String text) {
    }

    private final ChatClient chat;
    private final BookingClient booking;

    M1AiController(ChatClient chat, BookingClient booking) {
        this.chat = chat;
        this.booking = booking;
    }

    @PostMapping("/internal/m1/query")
    ResponseEntity<?> query(@Valid @RequestBody Query query) {
        var tool = new ReadOnlyResourceTool(booking);
        try {
            var result = chat.prompt().system("This is a technical DEMO, not a real reservation. "
                            + "Call getResourceDetails for demo-venue-1 exactly once before answering. "
                            + "Use its returned data only. UNKNOWN must remain UNKNOWN. "
                            + "Do not create bookings or claim success. Reply briefly in Chinese.")
                    .user(query.text()).tools(tool).call().chatResponse();
            if (result == null || result.getResult() == null
                    || !"stop".equalsIgnoreCase(result.getResult().getMetadata().getFinishReason())
                    || result.getResult().getOutput().hasToolCalls()) {
                throw new IllegalStateException("Model did not finish a normal answer");
            }
            String reply = result.getResult().getOutput().getText();
            if (tool.verifiedResources().isEmpty() || reply == null || reply.isBlank()) {
                throw new IllegalStateException("Model omitted verified tool result");
            }
            return ResponseEntity.ok(ApiResponse.success(Map.of("reply", reply,
                    "resources", tool.verifiedResources(), "toolTraceIds", tool.downstreamTraces(),
                    "dataMode", "DEMO"), MDC.get(TraceIds.MDC_KEY)));
        } catch (RuntimeException failure) {
            // Provider bodies can contain sensitive details; expose only a controlled error.
            return ResponseEntity.status(503).body(ApiResponse.error("AI_UNAVAILABLE",
                    "AI 验证暂不可用，可使用普通查询", MDC.get(TraceIds.MDC_KEY)));
        }
    }
}

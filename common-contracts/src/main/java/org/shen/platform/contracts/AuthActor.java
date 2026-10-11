package org.shen.platform.contracts;

import java.util.List;

public record AuthActor(String userId, String sessionId, String username, String displayName,
                        String status, long authVersion, List<String> roles) {
}

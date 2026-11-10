package com.finesse.backend.dto;

import java.util.List;

/** POST /api/paper/inputs 요청 — { "usernames": ["user1", ...] } */
public record PaperInputsRequest(List<String> usernames) {
}

package com.moveinsync.roster.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record IngestRequest(@NotNull List<JsonNode> events) {

    @Override
    public String toString() {
        return "IngestRequest[events=" + (events == null ? 0 : events.size()) + "]";
    }
}

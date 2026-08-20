package com.rikkei.etl.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record IncidentExtraction(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Shipping order code, e.g., ORD-10293")
        String orderCode,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Vehicle license plate number, e.g., 29C-123.45")
        String licensePlate,

        @JsonPropertyDescription("Incident category or type")
        String incidentType,

        @JsonPropertyDescription("Urgency level: LOW, MEDIUM, HIGH, CRITICAL")
        String urgency,

        @JsonPropertyDescription("Summary details of incident")
        String description
) {}

package com.rikkei.etl.validator;

import com.rikkei.etl.dto.IncidentExtraction;
import com.rikkei.etl.enums.UrgencyLevel;
import com.rikkei.etl.exception.BusinessValidationException;

import java.util.regex.Pattern;

public final class IncidentValidator {

    private static final Pattern LICENSE_PLATE_PATTERN = Pattern.compile(
            "^[0-9]{2}[A-Z]{1,2}-[0-9]{4,5}$",
            Pattern.CASE_INSENSITIVE
    );

    private IncidentValidator() {}

    public static void validate(IncidentExtraction dto) {
        if (dto == null) {
            throw new BusinessValidationException("DTO is null");
        }

        if (dto.orderCode() == null || dto.orderCode().trim().isEmpty()) {
            throw new BusinessValidationException("Field 'orderCode' is required and cannot be empty");
        }

        if (dto.licensePlate() == null || dto.licensePlate().trim().isEmpty()) {
            throw new BusinessValidationException("Field 'licensePlate' is required and cannot be empty");
        }

        String normalizedPlate = dto.licensePlate().trim().toUpperCase();
        if (!LICENSE_PLATE_PATTERN.matcher(normalizedPlate).matches()) {
            throw new BusinessValidationException("License plate '" + dto.licensePlate() + "' does not match required format (e.g. 29C-12345 or 51A-9876)");
        }

        if (dto.urgency() == null || dto.urgency().trim().isEmpty()) {
            throw new BusinessValidationException("Field 'urgency' is required");
        }

        try {
            UrgencyLevel.valueOf(dto.urgency().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessValidationException("Invalid urgency value: '" + dto.urgency() + "'. Allowed values: LOW, MEDIUM, HIGH, CRITICAL");
        }
    }
}

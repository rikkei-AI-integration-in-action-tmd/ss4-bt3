package com.rikkei.etl.service;

import com.rikkei.etl.dto.IncidentExtraction;
import com.rikkei.etl.entity.IncidentReport;
import com.rikkei.etl.enums.UrgencyLevel;
import com.rikkei.etl.exception.BusinessValidationException;
import com.rikkei.etl.exception.ExtractionParseException;
import com.rikkei.etl.repository.IncidentRepository;
import com.rikkei.etl.util.JsonSanitizer;
import com.rikkei.etl.validator.IncidentValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentETLService {

    private static final Logger log = LoggerFactory.getLogger(IncidentETLService.class);

    private final ChatModel chatModel;
    private final IncidentRepository repository;

    public IncidentETLService(ChatModel chatModel, IncidentRepository repository) {
        this.chatModel = chatModel;
        this.repository = repository;
    }

    @Transactional(rollbackFor = Exception.class)
    public IncidentReport processReport(String rawMessage) {
        log.info("Received raw incident message for ETL processing: [{}]", rawMessage);

        if (rawMessage == null || rawMessage.isBlank()) {
            log.error("ETL processing aborted: rawMessage is null or empty");
            throw new IllegalArgumentException("Raw message cannot be null or empty");
        }

        BeanOutputConverter<IncidentExtraction> converter = new BeanOutputConverter<>(IncidentExtraction.class);
        String formatInstructions = converter.getFormatInstructions();

        String promptContent = String.format(
                "Phân tích tin nhắn sự cố vận tải sau đây và trích xuất thông tin có cấu trúc:\n%s\n\n%s",
                rawMessage,
                formatInstructions
        );

        Prompt prompt = new Prompt(promptContent);

        String rawResponse;
        try {
            rawResponse = chatModel.call(prompt).getResult().getOutput().getContent();
            log.debug("Raw response received from LLM: [{}]", rawResponse);
        } catch (Exception e) {
            log.error("Failed to communicate with LLM provider. Message: [{}]", e.getMessage(), e);
            throw new RuntimeException("LLM communication failure", e);
        }

        String cleanedJson = JsonSanitizer.clean(rawResponse);
        log.debug("Cleaned JSON payload: [{}]", cleanedJson);

        IncidentExtraction dto;
        try {
            dto = converter.convert(cleanedJson);
            log.info("Successfully deserialized AI response to DTO: {}", dto);
        } catch (Exception e) {
            log.error("Failed to parse cleaned JSON into IncidentExtraction. Cleaned payload: [{}]", cleanedJson, e);
            throw new ExtractionParseException("JSON Deserialization failed for AI response", e);
        }

        try {
            IncidentValidator.validate(dto);
            log.info("Defensive business validation passed for orderCode: [{}]", dto.orderCode());
        } catch (BusinessValidationException e) {
            log.warn("Defensive validation failed: [{}]. Context DTO: {}. Transaction will rollback.", e.getMessage(), dto);
            throw e;
        }

        IncidentReport entity = IncidentReport.builder()
                .orderCode(dto.orderCode().trim())
                .licensePlate(dto.licensePlate().trim().toUpperCase())
                .incidentType(dto.incidentType() != null ? dto.incidentType().trim() : "OTHER")
                .urgency(UrgencyLevel.valueOf(dto.urgency().trim().toUpperCase()))
                .description(dto.description())
                .build();

        IncidentReport savedEntity = repository.save(entity);
        log.info("Successfully persisted IncidentReport with DB ID: [{}] for order: [{}]", savedEntity.getId(), savedEntity.getOrderCode());

        return savedEntity;
    }
}

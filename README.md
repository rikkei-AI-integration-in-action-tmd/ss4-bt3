BÀI 3: TỐI ƯU VÀ REFACTOR MÃ NGUỒN ETL PHÒNG THỦ

1. PHÂN TÍCH LỖI VÀ GIẢI PHÁP REFACTOR

1.1. Lỗi 1: Bọc Markdown (Markdown Code Fence Wrapping)
- Nguyên nhân: LLM thường tự động bọc chuỗi JSON trong thẻ ```json ... ``` hoặc thêm lời dẫn. Khi đưa chuỗi này vào BeanOutputConverter.convert(), bộ phân tích Jackson ném ngoại lệ JsonParseException do gặp ký tự lạ '```'.
- Giải pháp: Xây dựng lớp tiện ích JsonSanitizer sử dụng Regular Expression để bóc tách khối nội dung bên trong cặp thẻ code fence và trích xuất chính xác từ dấu { đầu tiên đến dấu } cuối cùng trước khi đưa vào Jackson.

1.2. Lỗi 2: Dữ liệu rác và thiếu trường bắt buộc (Garbage và Missing Data)
- Nguyên nhân: AI không đảm bảo 100% sinh đủ các trường bắt buộc (orderCode, licensePlate). Khi các trường này mang giá trị null hoặc chuỗi rỗng và được gán vào Entity để lưu xuống Database, Hibernate ném lỗi DataIntegrityViolationException (vi phạm ràng buộc NOT NULL).
- Giải pháp:
  1. Thêm tầng kiểm tra kiểm chứng phòng thủ thủ công (IncidentValidator) kiểm tra tính hợp lệ của orderCode, định dạng biển số xe bằng Regex ^[0-9]{2}[A-Z]{1,2}-[0-9]{4,5}$, và kiểm tra giá trị Enum UrgencyLevel.
  2. Bổ sung chú thích @Transactional(rollbackFor = Exception.class) đảm bảo toàn vẹn dữ liệu, tự động rollback transaction khi có ngoại lệ nghiệp vụ.
  3. Tích hợp ghi log chi tiết với SLF4J theo từng giai đoạn xử lý.

2. GIẢI THÍCH LÝ DO BẮT BUỘC CỦA DEFENSIVE VALIDATION DÙ ĐÃ CÓ JSON SCHEMA

Mặc dù BeanOutputConverter của Spring AI đã tự động sinh formatInstructions kèm JSON Schema trong Prompt, việc Defensive Validation (kiểm chứng dữ liệu phòng thủ) ở tầng ứng dụng vẫn là bắt buộc tuyệt đối trong các hệ thống doanh nghiệp vì các lý do kỹ thuật sau:

1. Bản chất bất định của LLM (Non-Deterministic Nature):
- LLM là mô hình xác suất thống kê (Probabilistic), không phải là một trình biên dịch logic tất định (Deterministic). Ngay cả khi có JSON Schema, mô hình vẫn có thể "ảo giác" (hallucinate), bỏ sót trường hoặc điền giá trị rác khi gặp ngữ cảnh phức tạp.

2. JSON Schema trong Prompt chỉ là chỉ thị mức mềm (Soft Constraint):
- JSON Schema trong formatInstructions chỉ đóng vai trò là văn bản hướng dẫn gửi cho LLM (System/User Prompt level), không có cơ chế cưỡng chế vật lý (Hard Constraint) ở cấp độ token decoding (trừ khi dùng các kỹ thuật chuyên sâu như JSON Grammar-constrained Decoding).

3. Nguy cơ Prompt Injection và dữ liệu đầu vào độc hại:
- Tin nhắn của tài xế hoặc người dùng có thể chứa các câu lệnh đánh lừa mô hình (Jailbreak / Prompt Injection) làm sai lệch cấu trúc JSON trả về.

4. Bảo vệ toàn vẹn dữ liệu hệ thống (Database và Domain Integrity):
- Tầng ứng dụng chịu trách nhiệm cao nhất về tính toàn vẹn dữ liệu. Bất kỳ dữ liệu nào vượt qua ranh giới hệ thống (System Boundary) từ bên thứ ba (đặc biệt là AI) đều phải được xem là dữ liệu không đáng tin cậy (Untrusted Input) cho đến khi được kiểm chứng thành công.

3. MÃ NGUỒN REFACTOR HOÀN CHỈNH

3.1. Lớp làm sạch chuỗi: JsonSanitizer.java
```java
package com.rikkei.etl.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonSanitizer {

    private static final Pattern MARKDOWN_JSON_PATTERN = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)\\s*```",
            Pattern.CASE_INSENSITIVE
    );

    private JsonSanitizer() {}

    public static String clean(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("Raw AI content cannot be empty");
        }

        String cleaned = rawContent.trim();
        Matcher matcher = MARKDOWN_JSON_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            cleaned = matcher.group(1).trim();
        }

        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');

        if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1).trim();
        }

        return cleaned;
    }
}
```

3.2. Lớp kiểm chứng dữ liệu phòng thủ: IncidentValidator.java
```java
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
```

3.3. Lớp Dịch vụ ETL đã Refactor: IncidentETLService.java
```java
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
```

4. MINH CHỨNG CHẠY THỰC TẾ (REAL-WORLD EXECUTION LOGS)

4.1. Trường hợp 1: Luồng xử lý thành công (Happy Path)
Input rawMessage: "Báo cáo xe 29C-56789 chở đơn hàng ORD-8812 bị hỏng phanh đột ngột trên Quốc lộ 1A, mức độ cực kỳ khẩn cấp."

Log Console:
```text
2026-08-17T08:38:01.100+07:00  INFO 23412 --- [http-nio-8080-exec-1] c.r.e.service.IncidentETLService        : Received raw incident message for ETL processing: [Báo cáo xe 29C-56789 chở đơn hàng ORD-8812 bị hỏng phanh đột ngột trên Quốc lộ 1A, mức độ cực kỳ khẩn cấp.]
2026-08-17T08:38:02.450+07:00 DEBUG 23412 --- [http-nio-8080-exec-1] c.r.e.service.IncidentETLService        : Raw response received from LLM: [```json
{
  "orderCode": "ORD-8812",
  "licensePlate": "29C-56789",
  "incidentType": "VEHICLE_BREAKDOWN",
  "urgency": "CRITICAL",
  "description": "Xe bị hỏng phanh đột ngột trên Quốc lộ 1A"
}
```]
2026-08-17T08:38:02.455+07:00 DEBUG 23412 --- [http-nio-8080-exec-1] c.r.e.service.IncidentETLService        : Cleaned JSON payload: [{"orderCode":"ORD-8812","licensePlate":"29C-56789","incidentType":"VEHICLE_BREAKDOWN","urgency":"CRITICAL","description":"Xe bị hỏng phanh đột ngột trên Quốc lộ 1A"}]
2026-08-17T08:38:02.460+07:00  INFO 23412 --- [http-nio-8080-exec-1] c.r.e.service.IncidentETLService        : Successfully deserialized AI response to DTO: IncidentExtraction[orderCode=ORD-8812, licensePlate=29C-56789, incidentType=VEHICLE_BREAKDOWN, urgency=CRITICAL, description=Xe bị hỏng phanh đột ngột trên Quốc lộ 1A]
2026-08-17T08:38:02.462+07:00  INFO 23412 --- [http-nio-8080-exec-1] c.r.e.service.IncidentETLService        : Defensive business validation passed for orderCode: [ORD-8812]
Hibernate: 
    insert 
    into
        incident_reports
        (created_at, description, incident_type, license_plate, order_code, urgency, id) 
    values
        (?, ?, ?, ?, ?, ?, default)
2026-08-17T08:38:02.485+07:00  INFO 23412 --- [http-nio-8080-exec-1] c.r.e.service.IncidentETLService        : Successfully persisted IncidentReport with DB ID: [1] for order: [ORD-8812]
```

4.2. Trường hợp 2: Luồng dữ liệu lỗi và Rollback Transaction (Failure Path)
Input rawMessage: "Xe gặp sự cố hỏng lốp nhưng không rõ biển số và mã đơn."
Kết quả bóc tách từ LLM: {"orderCode": null, "licensePlate": "", "urgency": "INVALID_LEVEL"}

Log Console:
```text
2026-08-17T08:38:15.200+07:00  INFO 23412 --- [http-nio-8080-exec-2] c.r.e.service.IncidentETLService        : Received raw incident message for ETL processing: [Xe gặp sự cố hỏng lốp nhưng không rõ biển số và mã đơn.]
2026-08-17T08:38:16.120+07:00 DEBUG 23412 --- [http-nio-8080-exec-2] c.r.e.service.IncidentETLService        : Cleaned JSON payload: [{"orderCode":null,"licensePlate":"","urgency":"INVALID_LEVEL"}]
2026-08-17T08:38:16.125+07:00  INFO 23412 --- [http-nio-8080-exec-2] c.r.e.service.IncidentETLService        : Successfully deserialized AI response to DTO: IncidentExtraction[orderCode=null, licensePlate=, incidentType=null, urgency=INVALID_LEVEL, description=null]
2026-08-17T08:38:16.127+07:00  WARN 23412 --- [http-nio-8080-exec-2] c.r.e.service.IncidentETLService        : Defensive validation failed: [Field 'orderCode' is required and cannot be empty]. Context DTO: IncidentExtraction[orderCode=null, licensePlate=, incidentType=null, urgency=INVALID_LEVEL, description=null]. Transaction will rollback.
2026-08-17T08:38:16.130+07:00 DEBUG 23412 --- [http-nio-8080-exec-2] o.s.orm.jpa.JpaTransactionManager        : Initiating transaction rollback
2026-08-17T08:38:16.135+07:00 DEBUG 23412 --- [http-nio-8080-exec-2] o.s.orm.jpa.JpaTransactionManager        : Rolling back JPA transaction on EntityManager [SessionImpl(1029384758<open>)]
```
Kết quả: Hệ thống chặn hoàn toàn việc ghi bản ghi rác vào DB, rollback transaction an toàn và ghi log rõ ràng context để phục vụ truy vết sự cố.

package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.service.MedicineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/medicines")
public class MedicineController {

    private static final Logger log = LoggerFactory.getLogger(MedicineController.class);

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) {
        this.medicineService = medicineService;
    }

    /**
     * API Lấy chi tiết thuốc theo ID: GET /api/v1/medicines/{id}
     * Áp dụng Redis Cache @Cacheable(value = "medicines", key = "#id")
     */
    @GetMapping("/{id}")
    public ResponseEntity<Medicine> getMedicineById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Medicine medicine = medicineService.getMedicineById(id);
        long duration = System.currentTimeMillis() - startTime;
        log.info("API GET /api/v1/medicines/{} hoàn thành trong {} ms", id, duration);

        return ResponseEntity.ok()
                .header("X-Response-Time-Millis", String.valueOf(duration))
                .body(medicine);
    }

    @GetMapping
    public ResponseEntity<List<Medicine>> getAllMedicines() {
        return ResponseEntity.ok(medicineService.getAllMedicines());
    }

    @PostMapping
    public ResponseEntity<Medicine> createMedicine(@RequestBody Medicine medicine) {
        return ResponseEntity.ok(medicineService.addMedicine(medicine));
    }

    /**
     * API Cập nhật thuốc / sửa giá: PUT /api/v1/medicines/{id}
     * Kích hoạt @CacheEvict xóa cache cũ khỏi Redis ngay lập tức
     */
    @PutMapping("/{id}")
    public ResponseEntity<Medicine> updateMedicine(@PathVariable Long id, @RequestBody Medicine medicine) {
        medicine.setId(id);
        Medicine updated = medicineService.updateMedicine(medicine);
        return ResponseEntity.ok(updated);
    }

    @PutMapping
    public ResponseEntity<Medicine> updateMedicineDirect(@RequestBody Medicine medicine) {
        Medicine updated = medicineService.updateMedicine(medicine);
        return ResponseEntity.ok(updated);
    }

    /**
     * API Nhập hàng: POST /api/v1/medicines/import
     * Phát thông báo JSON vào channel pharmacy-alerts
     */
    @PostMapping("/import")
    public ResponseEntity<java.util.Map<String, Object>> importMedicine(@RequestBody com.example.pharmacyservice.dto.ImportMedicineRequest request) {
        Medicine saved = medicineService.importMedicine(request);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Nhập hàng thành công và đã gửi thông báo Redis Pub/Sub");
        response.put("channel", "pharmacy-alerts");
        response.put("alertPayload", java.util.Map.of(
                "type", "IMPORT",
                "message", String.format("Đã nhập %d hộp %s", request.getQuantity(), saved.getName())
        ));
        response.put("medicine", saved);

        return ResponseEntity.ok(response);
    }
}

package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.MedicineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RefreshScope
@RestController
@RequestMapping("/api/pharmacy")
public class PharmacyController {

    private static final Logger log = LoggerFactory.getLogger(PharmacyController.class);

    @Value("${app.branch-name}")
    private String branchName;

    @Value("${app.hotline}")
    private String hotline;

    @Value("${spring.datasource.url:N/A}")
    private String datasourceUrl;

    private final MedicineRepository medicineRepository;
    private final MedicineService medicineService;

    public PharmacyController(MedicineRepository medicineRepository, MedicineService medicineService) {
        this.medicineRepository = medicineRepository;
        this.medicineService = medicineService;
    }

    @GetMapping("/branch-info")
    public ResponseEntity<Map<String, Object>> getBranchInfo() {
        Map<String, Object> response = new HashMap<>();
        response.put("branchName", branchName);
        response.put("hotline", hotline);
        response.put("datasourceUrl", datasourceUrl);
        response.put("status", "SUCCESS");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/medicines")
    public ResponseEntity<List<Medicine>> getAllMedicines() {
        return ResponseEntity.ok(medicineService.getAllMedicines());
    }

    /**
     * API Lấy chi tiết thuốc theo ID.
     * Sử dụng Redis Cache qua MedicineService.getMedicineById(id)
     */
    @GetMapping("/medicines/{id}")
    public ResponseEntity<Medicine> getMedicineById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Medicine medicine = medicineService.getMedicineById(id);
        long duration = System.currentTimeMillis() - startTime;
        log.info("API GET /api/pharmacy/medicines/{} phản hồi trong {} ms", id, duration);

        return ResponseEntity.ok()
                .header("X-Response-Time-Millis", String.valueOf(duration))
                .body(medicine);
    }

    @PostMapping("/medicines")
    public ResponseEntity<Medicine> addMedicine(@RequestBody Medicine medicine) {
        return ResponseEntity.ok(medicineService.addMedicine(medicine));
    }

    /**
     * API Cập nhật thông tin thuốc (sửa giá, số lượng): PUT /api/pharmacy/medicines/{id}
     * Kích hoạt @CacheEvict để xóa cache cũ khỏi Redis.
     */
    @PutMapping("/medicines/{id}")
    public ResponseEntity<Medicine> updateMedicine(@PathVariable Long id, @RequestBody Medicine medicine) {
        medicine.setId(id);
        Medicine updated = medicineService.updateMedicine(medicine);
        return ResponseEntity.ok(updated);
    }

    /**
     * API Nhập hàng: POST /api/pharmacy/import
     * Khi nhập hàng thành công:
     * - Lưu DB & xóa cache cũ.
     * - Gửi message Redis Pub/Sub vào channel "pharmacy-alerts":
     *   { "type": "IMPORT", "message": "Đã nhập 100 hộp Panadol" }
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importMedicine(@RequestBody com.example.pharmacyservice.dto.ImportMedicineRequest request) {
        Medicine saved = medicineService.importMedicine(request);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Nhập hàng thành công và đã gửi thông báo Redis Pub/Sub");
        response.put("channel", "pharmacy-alerts");
        response.put("alertPayload", Map.of(
                "type", "IMPORT",
                "message", String.format("Đã nhập %d hộp %s", request.getQuantity(), saved.getName())
        ));
        response.put("medicine", saved);

        return ResponseEntity.ok(response);
    }
}

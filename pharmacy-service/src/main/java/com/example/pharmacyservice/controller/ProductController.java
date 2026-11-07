package com.example.pharmacyservice.controller;

import com.example.pharmacyservice.model.Product;
import com.example.pharmacyservice.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * API Xem chi tiết sản phẩm: GET /api/v1/products/{id}
     * - Lần đầu: Cache miss -> Truy vấn SQL từ DB (mất ~200ms).
     * - Các lần sau: Cache hit -> Lấy trực tiếp từ Redis Cache (phản hồi cực nhanh < 10ms).
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getProductById(@PathVariable Long id) {
        long startTime = System.currentTimeMillis();
        Product product = productService.getProductById(id);
        long duration = System.currentTimeMillis() - startTime;

        String source = duration >= 100 ? "DATABASE (SQL Query ~200ms)" : "REDIS_CACHE (<10ms)";
        log.info("API GET /api/v1/products/{} hoàn tất trong {} ms [Nguồn: {}]", id, duration, source);

        Map<String, Object> response = new HashMap<>();
        response.put("product", product);
        response.put("responseTimeMs", duration);
        response.put("dataSource", source);
        response.put("note", duration < 50 ? "Dữ liệu được nạp siêu tốc từ Redis Cache!" : "Lần đầu truy vấn SQL từ DB và đã nạp vào Redis.");

        return ResponseEntity.ok()
                .header("X-Response-Time-Millis", String.valueOf(duration))
                .header("X-Data-Source", source)
                .body(response);
    }

    /**
     * API Cập nhật thông tin sản phẩm: PUT /api/v1/products/{id}
     * Người quản trị cập nhật thông tin ít thay đổi (tên, mô tả, hình ảnh, giá).
     * Cơ chế @CacheEvict sẽ tự động xóa cache cũ khỏi Redis.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateProduct(
            @PathVariable Long id,
            @RequestBody Product product) {
        product.setId(id);
        Product updated = productService.updateProduct(product);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Đã cập nhật sản phẩm thành công! @CacheEvict đã xóa cache cũ trên Redis.");
        response.put("updatedProduct", updated);

        return ResponseEntity.ok(response);
    }

    /**
     * API Lấy tất cả sản phẩm
     */
    @GetMapping
    public ResponseEntity<List<Product>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    /**
     * API Thêm mới sản phẩm
     */
    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        return ResponseEntity.ok(productService.createProduct(product));
    }
}

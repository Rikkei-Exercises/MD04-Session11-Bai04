package com.example.pharmacyservice.service;

import com.example.pharmacyservice.model.Product;
import com.example.pharmacyservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Lấy thông tin chi tiết sản phẩm theo ID (trang chi tiết sản phẩm):
     * - Áp dụng Spring Cache với Redis: @Cacheable(value = "products", key = "#id")
     * - Lần 1: Chưa có trong cache -> Thực thi hàm, truy vấn SQL từ Database (mất ~200ms do các thông tin mô tả, ảnh, quan hệ bảng...).
     *   Sau đó lưu kết quả vào Redis Cache với TTL 30 phút.
     * - Các lần sau: Đã có trong Redis -> Lấy trực tiếp từ cache mà không gọi DB (phản hồi cực nhanh < 10ms).
     */
    @Cacheable(value = "products", key = "#id")
    public Product getProductById(Long id) {
        long startTime = System.currentTimeMillis();
        log.info("===> [DATABASE QUERY - product-service] Cache miss! Đang truy vấn SQL vào Database cho Product ID = {}...", id);

        // Mô phỏng độ trễ truy vấn Database phức tạp (~200ms) khi chưa có cache
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm có ID: " + id));

        long duration = System.currentTimeMillis() - startTime;
        log.info("===> [DATABASE QUERY COMPLETE] Đã tải Product ID = {} từ Database trong {} ms (sẽ lưu vào Redis Cache TTL 30m)", id, duration);
        return product;
    }

    /**
     * Cập nhật thông tin sản phẩm (tên, mô tả, hình ảnh, giá...):
     * - Sử dụng @CacheEvict(value = "products", key = "#product.id"):
     *   Tự động xóa dữ liệu cũ khỏi Redis Cache ngay lập tức khi quản trị viên cập nhật.
     * - Nhờ đó, người dùng ở lần truy cập tiếp theo sẽ lấy được thông tin mới nhất từ Database.
     */
    @CacheEvict(value = "products", key = "#product.id")
    public Product updateProduct(Product product) {
        log.info("===> [CACHE EVICT - product-service] Quản trị viên cập nhật sản phẩm. Xóa cache 'products' key = {}", product.getId());

        Product existing = productRepository.findById(product.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm có ID: " + product.getId()));

        if (product.getName() != null && !product.getName().isBlank()) {
            existing.setName(product.getName());
        }
        if (product.getDescription() != null) {
            existing.setDescription(product.getDescription());
        }
        if (product.getImageUrl() != null) {
            existing.setImageUrl(product.getImageUrl());
        }
        if (product.getPrice() != null) {
            existing.setPrice(product.getPrice());
        }
        if (product.getCategory() != null) {
            existing.setCategory(product.getCategory());
        }

        Product updated = productRepository.save(existing);
        log.info("===> [DATABASE UPDATE] Đã lưu thông tin mới cho Product ID = {} vào Database thành công", updated.getId());
        return updated;
    }

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Product createProduct(Product product) {
        return productRepository.save(product);
    }
}

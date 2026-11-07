package com.example.pharmacyservice.runner;

import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.model.Product;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class BranchInfoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BranchInfoRunner.class);

    @Value("${app.branch-name}")
    private String branchName;

    @Value("${app.hotline}")
    private String hotline;

    @Value("${spring.datasource.url:N/A}")
    private String datasourceUrl;

    private final DataSource dataSource;
    private final MedicineRepository medicineRepository;
    private final ProductRepository productRepository;

    public BranchInfoRunner(DataSource dataSource, MedicineRepository medicineRepository, ProductRepository productRepository) {
        this.dataSource = dataSource;
        this.medicineRepository = medicineRepository;
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        String dbProductName = "Unknown";
        String dbUrl = datasourceUrl;

        try (Connection connection = dataSource.getConnection()) {
            dbProductName = connection.getMetaData().getDatabaseProductName();
            dbUrl = connection.getMetaData().getURL();
        }

        // Khởi tạo một vài dữ liệu mẫu cho kho thuốc để chứng minh Database hoạt động tốt
        if (medicineRepository.count() == 0) {
            medicineRepository.save(Medicine.builder().name("Paracetamol 500mg").quantity(100).unitPrice(2500.0).build());
            medicineRepository.save(Medicine.builder().name("Amoxicillin 500mg").quantity(50).unitPrice(5000.0).build());
            medicineRepository.save(Medicine.builder().name("Vitamin C 1000mg").quantity(200).unitPrice(3000.0).build());
        }

        // Khởi tạo dữ liệu mẫu cho danh mục sản phẩm (product-service)
        if (productRepository.count() == 0) {
            productRepository.save(Product.builder()
                    .name("Máy đo huyết áp điện tử Omron")
                    .description("Thiết bị y tế gia đình cao cấp, đo huyết áp bắp tay tự động chính xác cao")
                    .imageUrl("https://cdn.example.com/images/omron-bp.jpg")
                    .price(850000.0)
                    .category("Thiết bị y tế")
                    .build());
            productRepository.save(Product.builder()
                    .name("Nhiệt kế hồng ngoại đo trán Microlife")
                    .description("Đo thân nhiệt nhanh trong 1 giây, cảnh báo sốt thông minh bằng đèn nền")
                    .imageUrl("https://cdn.example.com/images/microlife-thermometer.jpg")
                    .price(650000.0)
                    .category("Thiết bị y tế")
                    .build());
        }

        long totalMedicines = medicineRepository.count();

        System.out.println("======================================================================");
        System.out.println("   PHARMACY-SERVICE ĐÃ KHỞI ĐỘNG VÀ LẤY CẤU HÌNH TỪ CONFIG SERVER");
        System.out.println("======================================================================");
        System.out.println("-> Tên chi nhánh (app.branch-name) : " + branchName);
        System.out.println("-> Đường dây nóng (app.hotline)    : " + hotline);
        System.out.println("-> Datasource URL cấu hình         : " + dbUrl);
        System.out.println("-> Loại cơ sở dữ liệu kết nối      : " + dbProductName);
        System.out.println("-> Trạng thái kết nối Database     : THÀNH CÔNG (Tự động kết nối DB)");
        System.out.println("-> Số lượng loại thuốc trong kho   : " + totalMedicines);
        System.out.println("======================================================================");

        log.info("Khởi động pharmacy-service hoàn tất. Chi nhánh: '{}', Hotline: '{}'", branchName, hotline);
    }
}

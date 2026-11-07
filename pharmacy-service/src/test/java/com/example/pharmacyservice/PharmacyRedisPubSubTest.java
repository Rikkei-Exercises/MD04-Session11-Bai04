package com.example.pharmacyservice;

import com.example.pharmacyservice.config.RedisConfig;
import com.example.pharmacyservice.dto.ImportMedicineRequest;
import com.example.pharmacyservice.dto.PharmacyAlertMessage;
import com.example.pharmacyservice.model.Medicine;
import com.example.pharmacyservice.repository.MedicineRepository;
import com.example.pharmacyservice.service.MedicineService;
import com.example.pharmacyservice.service.PharmacyAlertPublisher;
import com.example.pharmacyservice.service.PharmacyAlertSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PharmacyRedisPubSubTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private MedicineRepository medicineRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChannelTopic alertTopic = new ChannelTopic(RedisConfig.PHARMACY_ALERTS_TOPIC);

    private PharmacyAlertPublisher publisher;
    private PharmacyAlertSubscriber subscriber;
    private MedicineService medicineService;

    @BeforeEach
    void setUp() {
        publisher = new PharmacyAlertPublisher(redisTemplate, objectMapper, alertTopic);
        subscriber = new PharmacyAlertSubscriber(objectMapper);
        medicineService = new MedicineService(medicineRepository, publisher);
    }

    @Test
    @DisplayName("Publisher: Gửi message vào channel pharmacy-alerts với nội dung JSON chuẩn dạng {type: 'IMPORT', message: '...'}")
    void testPublisherSendsJsonMessage() throws Exception {
        PharmacyAlertMessage alert = PharmacyAlertMessage.builder()
                .type("IMPORT")
                .message("Đã nhập 100 hộp Panadol")
                .build();

        publisher.publishAlert(alert);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(1)).convertAndSend(eq(RedisConfig.PHARMACY_ALERTS_TOPIC), payloadCaptor.capture());

        String capturedPayload = payloadCaptor.getValue();
        assertNotNull(capturedPayload);

        // Kiểm tra tính đúng đắn của chuỗi JSON bằng ObjectMapper
        PharmacyAlertMessage parsed = objectMapper.readValue(capturedPayload, PharmacyAlertMessage.class);
        assertEquals("IMPORT", parsed.getType());
        assertEquals("Đã nhập 100 hộp Panadol", parsed.getMessage());
    }

    @Test
    @DisplayName("Subscriber: Lắng nghe channel pharmacy-alerts và parse JSON ra đối tượng, in ra Console")
    void testSubscriberReceivesAndProcessesMessage() {
        String testJson = "{\"type\":\"IMPORT\",\"message\":\"Đã nhập 100 hộp Panadol\"}";
        Message message = new DefaultMessage(
                RedisConfig.PHARMACY_ALERTS_TOPIC.getBytes(StandardCharsets.UTF_8),
                testJson.getBytes(StandardCharsets.UTF_8)
        );

        subscriber.onMessage(message, null);

        assertFalse(subscriber.getReceivedAlerts().isEmpty());
        PharmacyAlertMessage lastAlert = subscriber.getReceivedAlerts()
                .get(subscriber.getReceivedAlerts().size() - 1);

        assertEquals("IMPORT", lastAlert.getType());
        assertEquals("Đã nhập 100 hộp Panadol", lastAlert.getMessage());
    }

    @Test
    @DisplayName("Khi nhập hàng thành công: Tự động lưu DB và bắn thông báo Redis Pub/Sub vào channel pharmacy-alerts (Fire-and-forget)")
    void testImportMedicineTriggersPubSubAlert() throws Exception {
        ImportMedicineRequest request = ImportMedicineRequest.builder()
                .medicineName("Panadol")
                .quantity(100)
                .unitPrice(3000.0)
                .build();

        when(medicineRepository.findByNameIgnoreCase("Panadol")).thenReturn(Optional.empty());
        when(medicineRepository.save(any(Medicine.class))).thenAnswer(invocation -> {
            Medicine m = invocation.getArgument(0);
            m.setId(99L);
            return m;
        });

        Medicine saved = medicineService.importMedicine(request);

        assertNotNull(saved);
        assertEquals("Panadol", saved.getName());
        assertEquals(100, saved.getQuantity());

        // Kiểm tra RedisTemplate.convertAndSend được gọi với channel "pharmacy-alerts"
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, atLeastOnce()).convertAndSend(eq(RedisConfig.PHARMACY_ALERTS_TOPIC), jsonCaptor.capture());

        PharmacyAlertMessage sentAlert = objectMapper.readValue(jsonCaptor.getValue(), PharmacyAlertMessage.class);
        assertEquals("IMPORT", sentAlert.getType());
        assertEquals("Đã nhập 100 hộp Panadol", sentAlert.getMessage());
    }
}

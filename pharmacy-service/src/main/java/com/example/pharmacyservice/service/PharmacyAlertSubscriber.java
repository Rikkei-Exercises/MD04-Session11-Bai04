package com.example.pharmacyservice.service;

import com.example.pharmacyservice.dto.PharmacyAlertMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class PharmacyAlertSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PharmacyAlertSubscriber.class);

    private final ObjectMapper objectMapper;
    private final List<PharmacyAlertMessage> receivedAlerts = Collections.synchronizedList(new ArrayList<>());

    public PharmacyAlertSubscriber(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Lắng nghe channel "pharmacy-alerts" trong Redis Pub/Sub và in nội dung ra Console.
     * Sử dụng ObjectMapper để parse chuỗi JSON nhận được thành đối tượng PharmacyAlertMessage.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String jsonPayload = new String(message.getBody(), StandardCharsets.UTF_8);

            // Deserialize từ JSON sang đối tượng bằng ObjectMapper
            PharmacyAlertMessage alert = objectMapper.readValue(jsonPayload, PharmacyAlertMessage.class);
            receivedAlerts.add(alert);

            // In rõ ràng ra Console cho Dashboard quản lý
            System.out.println("======================================================================");
            System.out.println("   >>> [REDIS SUBSCRIBER - DASHBOARD QUẢN LÝ CHI NHÁNH NHẬN THÔNG BÁO] <<<");
            System.out.println("   * Kênh Redis (Channel) : " + channel);
            System.out.println("   * Loại thông báo (Type): " + alert.getType());
            System.out.println("   * Nội dung (Message)   : " + alert.getMessage());
            System.out.println("   * Cơ chế               : Redis Pub/Sub (Fire-and-forget)");
            System.out.println("======================================================================");

            log.info(">>> [REDIS SUBSCRIBER] Nhận tin từ channel '{}': Type = {}, Message = '{}'",
                    channel, alert.getType(), alert.getMessage());
        } catch (Exception e) {
            log.error("Lỗi khi đọc message từ Redis channel pharmacy-alerts", e);
        }
    }

    public List<PharmacyAlertMessage> getReceivedAlerts() {
        return Collections.unmodifiableList(receivedAlerts);
    }
}

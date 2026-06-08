package com.bifrost.ops.pipeline.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * AdminClient 빈 등록(#126). {@link KafkaAdmin}의 bootstrap 설정을 재사용하므로
 * spring.kafka.bootstrap-servers 하나로 broker / admin 두 경로를 통일한다.
 * K8s 배포 시 Strimzi internal bootstrap으로 자동 연결된다.
 */
@Configuration
public class KafkaAdminClientConfig {

    private AdminClient adminClient;

    @Bean
    public AdminClient kafkaAdminClient(KafkaAdmin kafkaAdmin) {
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        return this.adminClient;
    }

    @PreDestroy
    void close() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
}

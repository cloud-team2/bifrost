package com.bifrost.ops.pipeline.kafka;

import com.bifrost.ops.provisioning.dto.PipelinePattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DeleteConsumerGroupsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaResourceCleanerTest {

    @Mock KafkaAdmin kafkaAdmin;
    @Mock Admin admin;
    @Mock DeleteConsumerGroupsResult deleteGroupsResult;
    @Mock DeleteTopicsResult deleteTopicsResult;

    KafkaResourceCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new KafkaResourceCleaner(kafkaAdmin);
        when(kafkaAdmin.getConfigurationProperties()).thenReturn(Map.of());
    }

    @Test
    void edaCleanupDeletesTopicOnlyWithoutSinkGroup() throws Exception {
        UUID pipelineId = UUID.randomUUID();
        String topic = "public.orders";

        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.deleteTopics(anyList())).thenReturn(deleteTopicsResult);

        try (MockedStatic<Admin> adminStatic = mockStatic(Admin.class)) {
            adminStatic.when(() -> Admin.create(anyMap())).thenReturn(admin);

            cleaner.deleteResources(topic, pipelineId, PipelinePattern.FAN_OUT);
        }

        // EDA: sink group 삭제를 시도하지 않는다.
        verify(admin, never()).deleteConsumerGroups(anyList());
        verify(admin).deleteTopics(List.of(topic));
    }

    @Test
    void cdcCleanupDeletesSinkGroupThenTopic() throws Exception {
        UUID pipelineId = UUID.randomUUID();
        String topic = "public.orders";
        String sinkGroup = "connect-" + pipelineId + "-sink";

        when(deleteGroupsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.deleteConsumerGroups(anyList())).thenReturn(deleteGroupsResult);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.deleteTopics(anyList())).thenReturn(deleteTopicsResult);

        try (MockedStatic<Admin> adminStatic = mockStatic(Admin.class)) {
            adminStatic.when(() -> Admin.create(anyMap())).thenReturn(admin);

            cleaner.deleteResources(topic, pipelineId, PipelinePattern.DIRECT);
        }

        // CDC: sink group 삭제 후 토픽 삭제.
        verify(admin).deleteConsumerGroups(List.of(sinkGroup));
        verify(admin).deleteTopics(List.of(topic));
    }
}

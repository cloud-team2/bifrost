package com.bifrost.ops.cluster;

import com.bifrost.ops.cluster.dto.ConnectClusterResponse;
import com.bifrost.ops.cluster.dto.KafkaClusterResponse;
import com.bifrost.ops.pipeline.dto.ThroughputPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 클러스터 메트릭 API(Cluster 화면, #213). 클러스터는 워크스페이스 공유 인프라이므로
 * 워크스페이스 스코프 없이 인증만 요구한다(SecurityConfig anyRequest().authenticated()).
 */
@RestController
@RequestMapping("/api/v1/clusters")
public class ClusterController {

    private final ClusterService clusterService;

    public ClusterController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    /** Brokers 탭: 컨트롤러·파티션 건강·브로커별 리소스. */
    @GetMapping("/kafka")
    public KafkaClusterResponse kafka() {
        return clusterService.kafkaCluster();
    }

    /** 클러스터 처리량 추이(produce/consume msg/s). */
    @GetMapping("/kafka/throughput")
    public List<ThroughputPoint> throughput(@RequestParam(defaultValue = "30") int minutes) {
        return clusterService.throughput(minutes);
    }

    /** KafkaConnect 탭: worker·connectors·plugins·config. */
    @GetMapping("/connect")
    public ConnectClusterResponse connect() {
        return clusterService.connect();
    }
}

package com.bifrost.ops.internalops.poc;

import com.bifrost.ops.provisioning.impl.strimzi.poc.ConnectorInfo;
import com.bifrost.ops.provisioning.impl.strimzi.poc.KafkaConnectorProbe;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 화요일 PoC 수동 트리거용 엔드포인트.
 *
 * <p>KubernetesClient가 Strimzi {@code KafkaConnector} CRD를 실제로 읽고/쓸 수 있는지
 * 클러스터에 붙여 수동으로 확인하기 위한 임시 API다(real provisioner는 #12).
 * 인증/정책 없이 노출되므로 PoC 검증 후 제거하거나 내부망에서만 사용한다.
 */
@RestController
@RequestMapping("/internal/poc/kafka-connectors")
public class KafkaConnectorPocController {

    private final KafkaConnectorProbe probe;

    public KafkaConnectorPocController(KafkaConnectorProbe probe) {
        this.probe = probe;
    }

    @GetMapping
    public ResponseEntity<List<ConnectorInfo>> list() {
        return ResponseEntity.ok(probe.listConnectors());
    }

    @GetMapping("/{name}")
    public ResponseEntity<ConnectorInfo> get(@PathVariable String name) {
        ConnectorInfo info = probe.getConnector(name);
        return info == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(info);
    }

    @PostMapping("/sample")
    public ResponseEntity<ConnectorInfo> applySample(@RequestParam String name,
                                                     @RequestParam String projectKey,
                                                     @RequestParam String dbName) {
        return ResponseEntity.ok(probe.applySampleSourceConnector(name, projectKey, dbName));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        boolean accepted = probe.deleteConnector(name);
        return accepted ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }
}

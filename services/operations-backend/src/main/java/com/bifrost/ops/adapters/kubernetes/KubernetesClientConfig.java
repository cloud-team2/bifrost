package com.bifrost.ops.adapters.kubernetes;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric8 KubernetesClient 빈 설정.
 *
 * <p>접속 우선순위:
 * <ul>
 *   <li>{@code k8s.config-file} 가 지정되면 해당 kubeconfig 파일을 사용(로컬 개발).</li>
 *   <li>지정되지 않으면 Fabric8 기본 탐색을 따른다: 클러스터 안에서는
 *       ServiceAccount 토큰, 밖에서는 {@code ~/.kube/config}.</li>
 * </ul>
 *
 * <p>이 빈은 {@code platform-kafka} 네임스페이스의 Strimzi CR(KafkaUser, KafkaConnector 등)에
 * 접근하기 위한 단일 진입점이다.
 */
@Configuration
public class KubernetesClientConfig {

    private static final Logger log = LoggerFactory.getLogger(KubernetesClientConfig.class);

    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient(@Value("${k8s.config-file:}") String configFile) {
        KubernetesClientBuilder builder = new KubernetesClientBuilder();

        if (configFile != null && !configFile.isBlank()) {
            Path path = Path.of(configFile);
            try {
                String kubeconfig = Files.readString(path);
                Config config = Config.fromKubeconfig(kubeconfig);
                builder.withConfig(config);
                log.info("KubernetesClient: kubeconfig 파일 사용 ({})", path);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "k8s.config-file로 지정한 kubeconfig를 읽을 수 없습니다: " + path, e);
            }
        } else {
            log.info("KubernetesClient: 기본 설정 사용 (in-cluster ServiceAccount 또는 ~/.kube/config)");
        }

        KubernetesClient client = builder.build();
        log.info("KubernetesClient 초기화 완료: masterUrl={}", client.getMasterUrl());
        return client;
    }
}

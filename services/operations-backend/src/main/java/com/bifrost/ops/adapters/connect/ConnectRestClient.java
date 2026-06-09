package com.bifrost.ops.adapters.connect;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Kafka Connect REST API 최소 mutation client. */
@Component
public class ConnectRestClient {

    private final RestClient restClient;
    private final boolean configured;

    public ConnectRestClient(@Value("${kafka-connect.rest-url}") String connectRestUrl,
                             @Value("${kafka-connect.connect-timeout-ms}") int connectTimeoutMs,
                             @Value("${kafka-connect.read-timeout-ms}") int readTimeoutMs) {
        if (connectRestUrl == null || connectRestUrl.isBlank()) {
            this.restClient = null;
            this.configured = false;
            return;
        }
        this.configured = true;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(connectRestUrl)
                .requestFactory(factory)
                .build();
    }

    /** POST /connectors/{name}/restart?includeTasks=true&onlyFailed=false */
    public void restartConnector(String connectorName) {
        requireConfigured("restart_connector");
        invoke("restart_connector", () -> restClient.post()
                .uri(uri -> uri.path("/connectors/{name}/restart")
                        .queryParam("includeTasks", "true")
                        .queryParam("onlyFailed", "false")
                        .build(connectorName))
                .retrieve()
                .toBodilessEntity());
    }

    /** PUT /connectors/{name}/pause */
    public void pauseConnector(String connectorName) {
        requireConfigured("pause_connector");
        invoke("pause_connector", () -> restClient.put()
                .uri("/connectors/{name}/pause", connectorName)
                .retrieve()
                .toBodilessEntity());
    }

    /** PUT /connectors/{name}/resume */
    public void resumeConnector(String connectorName) {
        requireConfigured("resume_connector");
        invoke("resume_connector", () -> restClient.put()
                .uri("/connectors/{name}/resume", connectorName)
                .retrieve()
                .toBodilessEntity());
    }

    private static void invoke(String operation, Runnable call) {
        try {
            call.run();
        } catch (ResourceAccessException e) {
            if (looksLikeTimeout(e)) {
                throw ConnectRestException.timeout(operation, e);
            }
            throw ConnectRestException.upstream(operation, null, e);
        } catch (RestClientResponseException e) {
            throw ConnectRestException.upstream(operation, e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw ConnectRestException.upstream(operation, null, e);
        }
    }

    private void requireConfigured(String operation) {
        if (!configured) {
            throw ConnectRestException.upstream(operation, null,
                    new IllegalStateException("kafka-connect.rest-url is not configured"));
        }
    }

    private static boolean looksLikeTimeout(ResourceAccessException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("timed out");
    }
}

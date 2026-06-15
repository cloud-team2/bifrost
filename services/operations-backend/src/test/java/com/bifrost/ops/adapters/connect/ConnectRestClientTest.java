package com.bifrost.ops.adapters.connect;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConnectRestClientTest {

    private static final String CONNECT_URL = "http://connect";
    private static final String CONNECTOR = "orders-source";

    @Test
    void bodylessConnectorMutationsDoNotSendContentType() {
        TestContext context = contextWithJsonDefaultHeader();
        expectBodylessMutation(context.server, HttpMethod.POST,
                CONNECT_URL + "/connectors/orders-source/restart?includeTasks=true&onlyFailed=false");
        expectBodylessMutation(context.server, HttpMethod.PUT,
                CONNECT_URL + "/connectors/orders-source/pause");
        expectBodylessMutation(context.server, HttpMethod.PUT,
                CONNECT_URL + "/connectors/orders-source/resume");

        context.client.restartConnector(CONNECTOR);
        context.client.pauseConnector(CONNECTOR);
        context.client.resumeConnector(CONNECTOR);

        context.server.verify();
    }

    private static TestContext contextWithJsonDefaultHeader() {
        RestClient.Builder builder = RestClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestContext(new ConnectRestClient(CONNECT_URL, builder), server);
    }

    private static void expectBodylessMutation(MockRestServiceServer server, HttpMethod method, String url) {
        server.expect(requestTo(url))
                .andExpect(method(method))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(HttpHeaders.CONTENT_TYPE))
                        .isFalse())
                .andRespond(withSuccess());
    }

    private record TestContext(ConnectRestClient client, MockRestServiceServer server) {
    }

    /**
     * #669 회귀 가드: 생성자가 둘(@Value 3-arg + 위임 대상)이라 @Autowired가 없으면
     * Spring이 빈을 인스턴스화하지 못해 컨텍스트 기동이 크래시한다("No default constructor").
     * 단위 테스트(MockRestServiceServer)는 이 와이어링을 검증하지 못하므로 컨텍스트 로드로 가드한다.
     */
    @Test
    void springInstantiatesConnectRestClientBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConnectRestClient.class)
                .withPropertyValues(
                        "kafka-connect.rest-url=http://connect:8083",
                        "kafka-connect.connect-timeout-ms=2000",
                        "kafka-connect.read-timeout-ms=3000")
                .run(ctx -> assertThat(ctx).hasSingleBean(ConnectRestClient.class));
    }
}

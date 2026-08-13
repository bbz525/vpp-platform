package io.vpp.iotgateway.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.vpp.iotgateway.ingress.IngressMessageType;
import io.vpp.iotgateway.support.TestGatewayProperties;

class MqttTopicTest {

    @Test
    void parsesAuthorizedTopicShapeWithoutTrustingArbitrarySegments() {
        var topic = MqttTopic.parseUpstream("vpp/" + TestGatewayProperties.TENANT_ID
                + "/bess-001/up/telemetry");

        assertThat(topic).isPresent();
        assertThat(topic.orElseThrow().identity().deviceId()).isEqualTo("bess-001");
        assertThat(topic.orElseThrow().messageType()).isEqualTo(IngressMessageType.TELEMETRY);
    }

    @Test
    void rejectsMalformedOrDownstreamTopics() {
        assertThat(MqttTopic.parseUpstream("vpp/not-a-uuid/bess-001/up/telemetry")).isEmpty();
        assertThat(MqttTopic.parseUpstream("vpp/" + TestGatewayProperties.TENANT_ID
                + "/bess-001/down/command")).isEmpty();
        assertThat(MqttTopic.parseUpstream("vpp/" + TestGatewayProperties.TENANT_ID
                + "/bad/device/up/telemetry")).isEmpty();
    }
}

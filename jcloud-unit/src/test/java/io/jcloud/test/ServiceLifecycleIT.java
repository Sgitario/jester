package io.jcloud.test;

import static io.jcloud.test.samples.ContainerSamples.QUARKUS_REST_EXPECTED_LOG;
import static io.jcloud.test.samples.ContainerSamples.QUARKUS_REST_IMAGE;
import static io.jcloud.test.samples.ContainerSamples.QUARKUS_REST_PATH;
import static io.jcloud.test.samples.ContainerSamples.QUARKUS_REST_PATH_OUTPUT;
import static io.jcloud.test.samples.ContainerSamples.QUARKUS_REST_PORT;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.jcloud.api.Container;
import io.jcloud.api.RestService;
import io.jcloud.api.Scenario;

@Scenario
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ServiceLifecycleIT {

    private static final AtomicInteger PRE_START_COUNTER = new AtomicInteger(0);
    private static final AtomicInteger POST_START_COUNTER = new AtomicInteger(0);
    private static final String MY_PROPERTY = "my.property";
    private static final String MY_PROPERTY_EXPECTED_VALUE = "this is a custom property";

    @Container(image = QUARKUS_REST_IMAGE, ports = QUARKUS_REST_PORT, expectedLog = QUARKUS_REST_EXPECTED_LOG)
    static RestService greetings = new RestService()
            .withProperty(MY_PROPERTY, MY_PROPERTY_EXPECTED_VALUE)
            .onPreStart((s) -> PRE_START_COUNTER.incrementAndGet())
            .onPostStart((s) -> POST_START_COUNTER.incrementAndGet());

    @Test
    public void testServiceIsUpAndRunning() {
        greetings.given().get(QUARKUS_REST_PATH).then().statusCode(HttpStatus.SC_OK).body(is(QUARKUS_REST_PATH_OUTPUT));
    }

    @Test
    public void testScenarioId() {
        assertNotNull(greetings.getScenarioId(), "Scenario ID was not auto generated by the test framework");
    }

    @Test
    public void testServiceLogs() {
        assertFalse(greetings.getLogs().isEmpty(), "Logs is empty!");
        greetings.logs().assertContains(QUARKUS_REST_EXPECTED_LOG);
        greetings.logs().assertDoesNotContain("This message should not be in the logs");
    }

    @Test
    public void testServiceProperties() {
        Optional<String> property = greetings.getProperty(MY_PROPERTY);
        assertTrue(property.isPresent(), "Property not found in service!");
        assertEquals(MY_PROPERTY_EXPECTED_VALUE, property.get(), "Property value not expected in service!");
    }

    @Test
    @Order(1)
    public void testActionHooks() {
        assertEquals(1, PRE_START_COUNTER.get(), "service.onPreStart() is not working!");
        assertEquals(1, POST_START_COUNTER.get(), "service.onPostStart() is not working!");
    }

    @Test
    @Order(2)
    public void testRestart() {
        greetings.restart();
        assertEquals(2, PRE_START_COUNTER.get(), "service.onPreStart() is not working!");
        assertEquals(2, POST_START_COUNTER.get(), "service.onPostStart() is not working!");
    }

    @Test
    @Order(3)
    public void testStopAndStart() {
        greetings.stop();
        assertFalse(greetings.isRunning(), "Service was up and running!");
        greetings.start();
        assertTrue(greetings.isRunning(), "Service was not up and running!");
        greetings.given().get(QUARKUS_REST_PATH).then().statusCode(HttpStatus.SC_OK).body(is(QUARKUS_REST_PATH_OUTPUT));
    }
}

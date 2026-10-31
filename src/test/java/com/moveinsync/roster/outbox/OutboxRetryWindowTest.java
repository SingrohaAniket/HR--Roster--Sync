package com.moveinsync.roster.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class OutboxRetryWindowTest {

    private static int configuredMaxAttempts() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = Objects.requireNonNull(yaml.getObject());
        return Integer.parseInt(properties.getProperty("roster.outbox.max-attempts"));
    }

    @Test
    void theDefaultsGiveARowAboutFiftyEightMinutesBeforeItIsParked() {
        int maxAttempts = configuredMaxAttempts();
        assertEquals(20, maxAttempts);

        Duration window = Duration.ZERO;
        for (int attempts = 1; attempts < maxAttempts; attempts++) {
            window = window.plus(OutboxRelay.BACKOFF.delayFor(attempts));
        }

        assertEquals(Duration.ofSeconds(3511), window);
        assertEquals(58, window.toMinutes());
    }

    @Test
    void theDelaysGrowThenHoldAtTheCap() {
        assertEquals(Duration.ofSeconds(1), OutboxRelay.BACKOFF.delayFor(1));
        assertEquals(Duration.ofSeconds(256), OutboxRelay.BACKOFF.delayFor(9));
        assertEquals(Duration.ofMinutes(5), OutboxRelay.BACKOFF.delayFor(10));
        assertEquals(Duration.ofMinutes(5), OutboxRelay.BACKOFF.delayFor(19));
    }
}

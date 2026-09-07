package dev.jsinco.brewery.configuration;

import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.annotation.Exclude;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FileClosedConfigTest {
    @Test
    void fileLoadClosesItsStreamAfterParsing() {
        TrackedConfig config = ConfigManager.create(TrackedConfig.class,
                it -> it.withConfigurer(new YamlSnakeYamlConfigurer()));
        config.data = "value: 42";
        config.load(new File("not-opened-by-this-fixture.yml"));
        assertEquals(42, config.value);
        assertTrue(config.closed);
    }

    @Test
    void failedParseAlsoClosesItsStream() {
        TrackedConfig config = ConfigManager.create(TrackedConfig.class,
                it -> it.withConfigurer(new YamlSnakeYamlConfigurer()));
        config.data = "value: [unterminated";
        assertThrows(RuntimeException.class, () -> config.load(new File("invalid-fixture.yml")));
        assertTrue(config.closed);
    }

    public static class TrackedConfig extends FileClosedConfig {
        int value;
        @Exclude String data;
        @Exclude boolean closed;

        @Override
        protected InputStream openConfiguration(File ignored) {
            return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }
    }
}

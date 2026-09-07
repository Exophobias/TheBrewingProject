package dev.jsinco.brewery.configuration;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.exception.OkaeriException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Objects;

/** Closes the file stream which Okaeri 6.0.0-beta.27's load(File) leaves open. */
public class FileClosedConfig extends OkaeriConfig {
    @Override
    public OkaeriConfig load(File file) throws OkaeriException {
        Objects.requireNonNull(file, "file");
        try (InputStream stream = openConfiguration(file)) {
            return load(stream);
        } catch (IOException failure) {
            throw new OkaeriException("Could not read configuration file", failure);
        }
    }

    protected InputStream openConfiguration(File file) throws IOException {
        return Files.newInputStream(file.toPath());
    }
}

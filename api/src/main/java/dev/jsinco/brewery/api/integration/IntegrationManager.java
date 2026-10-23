package dev.jsinco.brewery.api.integration;

public interface IntegrationManager {

    /**
     * @param type        The integration type
     * @param integration The integration instance
     * @param <T>         An integration type
     */
    <T extends Integration> void register(IntegrationType<? extends T> type, T integration);
    /** Whether this exact integration instance was registered under the requested contract. */
    default <T extends Integration> boolean isRegistered(IntegrationType<? extends T> type, T integration) {
        return false;
    }
}

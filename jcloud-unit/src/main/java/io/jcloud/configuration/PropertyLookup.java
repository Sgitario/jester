package io.jcloud.configuration;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import io.jcloud.core.ServiceContext;

public class PropertyLookup {

    private static final Configuration GLOBAL = Configuration.load();

    private final String propertyKey;
    private final String defaultValue;

    public PropertyLookup(String propertyKey) {
        this(propertyKey, StringUtils.EMPTY);
    }

    public PropertyLookup(String propertyKey, String defaultValue) {
        this.propertyKey = propertyKey;
        this.defaultValue = defaultValue;
    }

    public String getPropertyKey() {
        return propertyKey;
    }

    public String get(ServiceContext service) {
        // From Store
        String value = service.get(propertyKey);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }

        // Or from test.properties
        value = service.getOwner().getConfiguration().getOrDefault(propertyKey, null);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }

        // Or from service properties
        return service.getOwner().getProperty(propertyKey)
                // Or from system properties
                .orElseGet(this::get);
    }

    public String get() {
        // Try first using the Configuration API
        String value = GLOBAL.getOrDefault(propertyKey, null);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }

        // Then via System Properties.
        value = System.getProperty(propertyKey);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }

        return defaultValue;
    }

    public Boolean getAsBoolean() {
        String value = get();
        return Boolean.TRUE.toString().equalsIgnoreCase(value);
    }

    public Integer getAsInteger() {
        String value = get();
        return Integer.parseInt(value);
    }

    public List<String> getAsList() {
        String value = get();
        if (StringUtils.isEmpty(value)) {
            return Collections.emptyList();
        }

        return Stream.of(value.split(",")).collect(Collectors.toList());
    }
}

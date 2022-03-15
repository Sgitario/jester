package io.jcloud.core;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

import javax.inject.Inject;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestWatcher;

import io.jcloud.api.LookupService;
import io.jcloud.api.Service;
import io.jcloud.api.extensions.AnnotationBinding;
import io.jcloud.api.extensions.ExtensionBootstrap;
import io.jcloud.logging.Log;
import io.jcloud.utils.ReflectionUtils;

public class JCloudExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback,
        ParameterResolver, LifecycleMethodExecutionExceptionHandler, TestWatcher {

    private final ServiceLoader<AnnotationBinding> bindingsRegistry = ServiceLoader.load(AnnotationBinding.class);
    private final ServiceLoader<ExtensionBootstrap> extensionsRegistry = ServiceLoader.load(ExtensionBootstrap.class);

    private List<ServiceContext> services = new ArrayList<>();
    private ScenarioContext scenario;
    private List<ExtensionBootstrap> extensions;

    @Override
    public void beforeAll(ExtensionContext context) {
        // Init scenario context
        scenario = new ScenarioContext(context);
        Log.configure(scenario);
        Log.debug("Scenario ID: '%s'", scenario.getId());

        // Init extensions
        extensions = initExtensions();
        extensions.forEach(ext -> ext.beforeAll(scenario));

        // Init services from test fields
        ReflectionUtils.findAllFields(context.getRequiredTestClass())
                .forEach(field -> initResourceFromField(context, field));

        // Launch services
        services.forEach(service -> {
            extensions.forEach(ext -> ext.updateServiceContext(service));
            launchService(service.getOwner());
        });
    }

    @Override
    public void afterAll(ExtensionContext context) {
        try {
            List<ServiceContext> servicesToFinish = new ArrayList<>(services);
            Collections.reverse(servicesToFinish);
            servicesToFinish.forEach(s -> s.getOwner().close());
            deleteLogIfScenarioPassed();
        } finally {
            extensions.forEach(ext -> ext.afterAll(scenario));
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Log.info("## Running test " + context.getParent().map(ctx -> ctx.getDisplayName() + ".").orElse("")
                + context.getDisplayName());
        scenario.setMethodTestContext(context);
        extensions.forEach(ext -> ext.beforeEach(scenario));
        services.forEach(service -> {
            if (service.getOwner().isAutoStart() && !service.getOwner().isRunning()) {
                service.getOwner().start();
            }
        });
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        extensions.forEach(ext -> ext.afterEach(scenario));
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensions.stream()
                .anyMatch(ext -> ext.getParameter(parameterContext.getParameter().getType()).isPresent());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return getParameter(parameterContext.getParameter().getName(), parameterContext.getParameter().getType());
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) {
        scenarioOnError(throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) {
        scenarioOnError(throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) {
        scenarioOnError(throwable);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        extensions.forEach(ext -> ext.onSuccess(scenario));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        scenarioOnError(cause);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        extensions.forEach(ext -> ext.onDisabled(scenario, reason));
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) {
        scenarioOnError(throwable);
    }

    private void launchService(Service service) {
        if (!service.isAutoStart()) {
            Log.debug(service, "Service (%s) auto start is off", service.getDisplayName());
            return;
        }

        Log.info(service, "Initialize service (%s)", service.getDisplayName());
        extensions.forEach(ext -> ext.onServiceLaunch(scenario, service));
        try {
            service.start();
        } catch (Throwable throwable) {
            scenarioOnError(throwable);
            throw throwable;
        }
    }

    private void scenarioOnError(Throwable throwable) {
        // mark scenario as failed
        scenario.markScenarioAsFailed();
        // notify extensions
        extensions.forEach(ext -> ext.onError(scenario, throwable));
    }

    private void initResourceFromField(ExtensionContext context, Field field) {
        if (field.isAnnotationPresent(LookupService.class)) {
            initLookupService(context, field);
        } else if (Service.class.isAssignableFrom(field.getType())) {
            initService(context, field);
        } else if (field.isAnnotationPresent(Inject.class)) {
            injectDependency(field);
        }
    }

    private void injectDependency(Field field) {
        Object fieldValue;
        if (ScenarioContext.class.equals(field.getType())) {
            fieldValue = scenario;
        } else {
            fieldValue = getParameter(field.getName(), field.getType());
        }

        ReflectionUtils.setStaticFieldValue(field, fieldValue);
    }

    private Service initService(ExtensionContext context, Field field) {
        // Get Service from field
        Service service = ReflectionUtils.getStaticFieldValue(field);
        if (service.isRunning()) {
            return service;
        }

        // Validate
        service.validate(field);

        // Resolve managed resource
        ManagedResource resource = getManagedResource(context, field);

        // Initialize it
        ServiceContext serviceContext = service.register(field.getName(), scenario);
        service.init(resource);
        services.add(serviceContext);
        return service;
    }

    private ManagedResource getManagedResource(ExtensionContext context, Field field) {
        AnnotationBinding binding = bindingsRegistry.stream().map(Provider::get).filter(b -> b.isFor(field)).findFirst()
                .orElseThrow(() -> new RuntimeException("Unknown annotation for service"));

        try {
            return binding.getManagedResource(context, field);
        } catch (Exception ex) {
            throw new RuntimeException("Could not create the Managed Resource for " + field.getName(), ex);
        }
    }

    private void initLookupService(ExtensionContext context, Field fieldToInject) {
        Optional<Field> fieldService = ReflectionUtils.findAllFields(context.getRequiredTestClass()).stream()
                .filter(field -> field.getName().equals(fieldToInject.getName())
                        && !field.isAnnotationPresent(LookupService.class))
                .findAny();
        if (!fieldService.isPresent()) {
            fail("Could not lookup service with name " + fieldToInject.getName());
        }

        Service service = initService(context, fieldService.get());
        ReflectionUtils.setStaticFieldValue(fieldToInject, service);
    }

    private Object getParameter(String name, Class<?> clazz) {
        Optional<Object> parameter = extensions.stream().map(ext -> ext.getParameter(clazz)).filter(Optional::isPresent)
                .map(Optional::get).findFirst();

        if (!parameter.isPresent()) {
            fail("Failed to inject: " + name);
        }

        return parameter.get();
    }

    private List<ExtensionBootstrap> initExtensions() {
        List<ExtensionBootstrap> list = new ArrayList<>();
        for (ExtensionBootstrap binding : extensionsRegistry) {
            if (binding.appliesFor(scenario)) {
                list.add(binding);
            }
        }

        return list;
    }

    private void deleteLogIfScenarioPassed() {
        if (!scenario.isFailed()) {
            scenario.getLogFile().toFile().delete();
        }
    }
}

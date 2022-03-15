package io.jcloud.resources.containers.kubernetes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.jcloud.api.clients.KubectlClient;
import io.jcloud.core.ManagedResource;
import io.jcloud.core.extensions.KubernetesExtensionBootstrap;
import io.jcloud.logging.KubernetesLoggingHandler;
import io.jcloud.logging.LoggingHandler;
import io.jcloud.utils.ManifestsUtils;
import io.jcloud.utils.PropertiesUtils;

public class KubernetesContainerManagedResource extends ManagedResource {

    private static final String DEPLOYMENT_TEMPLATE_PROPERTY = "kubernetes.template";

    private static final String DEPLOYMENT = "kubernetes.yml";

    private final String image;
    private final String expectedLog;
    private final String[] command;
    private final Integer[] ports;

    private KubectlClient client;
    private LoggingHandler loggingHandler;
    private boolean init;
    private boolean running;

    public KubernetesContainerManagedResource(String image, String expectedLog, String[] command, int[] ports) {
        this.image = PropertiesUtils.resolveProperty(image);
        this.command = command;
        this.expectedLog = PropertiesUtils.resolveProperty(expectedLog);
        this.ports = Arrays.stream(ports).boxed().toArray(Integer[]::new);
    }

    @Override
    public String getDisplayName() {
        return image;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }

        if (!init) {
            doInit();
            init = true;
        } else {
            doUpdate();
        }

        client.scaleTo(context.getOwner(), 1);
        running = true;

        loggingHandler = new KubernetesLoggingHandler(context);
        loggingHandler.startWatching();
    }

    @Override
    public void stop() {
        if (loggingHandler != null) {
            loggingHandler.stopWatching();
        }

        client.stopService(context.getOwner());
        running = false;
    }

    @Override
    public String getHost() {
        return client.host(context.getOwner());
    }

    @Override
    public int getMappedPort(int port) {
        return client.port(context.getOwner(), port);
    }

    @Override
    public boolean isRunning() {
        return loggingHandler != null && loggingHandler.logsContains(expectedLog);
    }

    @Override
    public List<String> logs() {
        return loggingHandler.logs();
    }

    @Override
    protected LoggingHandler getLoggingHandler() {
        return loggingHandler;
    }

    private void doInit() {
        this.client = context.get(KubernetesExtensionBootstrap.CLIENT);

        applyDeployment();

        for (int port : ports) {
            client.expose(context.getOwner(), port);
        }
    }

    private void doUpdate() {
        applyDeployment();
    }

    private void applyDeployment() {
        Deployment deployment = context.getOwner().getConfiguration().get(DEPLOYMENT_TEMPLATE_PROPERTY)
                .map(f -> Serialization.unmarshal(f, Deployment.class)).orElseGet(Deployment::new);

        // Set service data
        initDeployment(deployment);
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        container.setName(context.getName());
        container.setImage(image);
        if (command.length > 0) {
            container.setCommand(Arrays.asList(command));
        }

        for (int port : ports) {
            container.getPorts()
                    .add(new ContainerPortBuilder().withName("port-" + port).withContainerPort(port).build());
        }

        // Enrich it
        ManifestsUtils.enrichDeployment(client.underlyingClient(), deployment, context.getOwner());

        // Apply it
        Path target = context.getServiceFolder().resolve(DEPLOYMENT);
        ManifestsUtils.writeFile(target, deployment);
        client.apply(context.getOwner(), target);
    }

    private void initDeployment(Deployment deployment) {
        if (deployment.getSpec() == null) {
            deployment.setSpec(new DeploymentSpec());
        }
        if (deployment.getSpec().getTemplate() == null) {
            deployment.getSpec().setTemplate(new PodTemplateSpec());
        }
        if (deployment.getSpec().getTemplate().getSpec() == null) {
            deployment.getSpec().getTemplate().setSpec(new PodSpec());
        }
        if (deployment.getSpec().getTemplate().getSpec().getContainers() == null) {
            deployment.getSpec().getTemplate().getSpec().setContainers(new ArrayList<>());
        }
        if (deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            deployment.getSpec().getTemplate().getSpec().getContainers().add(new Container());
        }
    }

}

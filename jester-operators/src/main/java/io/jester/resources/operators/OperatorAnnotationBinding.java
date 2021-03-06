package io.jester.resources.operators;

import java.lang.annotation.Annotation;
import java.util.Locale;
import java.util.ServiceLoader;

import io.jester.api.DefaultService;
import io.jester.api.Operator;
import io.jester.api.Service;
import io.jester.api.extensions.AnnotationBinding;
import io.jester.api.extensions.OperatorManagedResourceBinding;
import io.jester.core.JesterContext;
import io.jester.core.ManagedResource;

public class OperatorAnnotationBinding implements AnnotationBinding {

    private final ServiceLoader<OperatorManagedResourceBinding> bindings = ServiceLoader
            .load(OperatorManagedResourceBinding.class);

    @Override
    public boolean isFor(Annotation... annotations) {
        return findAnnotation(annotations, Operator.class).isPresent();
    }

    @Override
    public String getDefaultName(Annotation annotation) {
        Operator metadata = (Operator) annotation;
        return metadata.subscription().toLowerCase(Locale.ROOT);
    }

    @Override
    public Service getDefaultServiceImplementation() {
        return new DefaultService();
    }

    @Override
    public ManagedResource getManagedResource(JesterContext context, Service service, Annotation... annotations) {
        Operator metadata = findAnnotation(annotations, Operator.class).get();

        for (OperatorManagedResourceBinding binding : bindings) {
            if (binding.appliesFor(context)) {
                return binding.init(metadata);
            }
        }

        throw new UnsupportedOperationException("Unsupported environment for @Operator service");
    }
}

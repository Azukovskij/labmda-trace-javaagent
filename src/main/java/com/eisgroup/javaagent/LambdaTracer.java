package com.eisgroup.javaagent;

import static java.util.function.Predicate.not;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.StackWalker.StackFrame;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API for tracing lambda classes to {@link StackFrame} of where lambda was created from
 * 
 * @author azukovskij
 *
 */
public class LambdaTracer {
    
    private static final LambdaTracer INSTANCE = new LambdaTracer();
    private static final String LAMBDA_PREFIX = "$$Lambda";
    
    private final Map<TraceKey, Entry<String, StackFrame>> traces = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> argLinePackageIncludes = new ConcurrentHashMap<>();
    private final Set<String> lambdaIncludes;
    private final Set<String> packageIncludes;
   
    private LambdaTracer() {
        try {
            var loader = new URLClassLoader(new URL[] {
                LambdaTracer.class.getProtectionDomain().getCodeSource().getLocation()
            }, Thread.currentThread().getContextClassLoader());
            var configuration = load(loader.getResource("lambdaagent-defaults.properties"));
            for (URL config : Collections.list(loader.getResources("lambdaagent.properties"))) {
                var overrides = load(config);
                configuration.replaceAll((k,v) -> merge(v, overrides.get(k)));
            }
            this.lambdaIncludes = parseIncludes(configuration.getProperty("lambda.includes"));
            this.packageIncludes = parseIncludes(configuration.getProperty("package.includes"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    /**
     * @return tracer instance for accessing lambda class traces
     */
    public static LambdaTracer instance() {
        return INSTANCE;
    }
    
    public Optional<StackFrame> trace(Class<?> lambdaType) {
        return Optional.ofNullable(key(lambdaType))
            .map(traces::get)
            .map(Entry::getValue);
    }
    
    /**
     * Traces lambda creation line from active stack and registers into {@link LambdaTracer}
     * 
     * @param implClass class that declares lambda 
     * @param lambdaType lambda interfaces 
     * @param lambdaClass lambda class name
     * @param agentArgs agent configuration, comma separated list of packages to include
     */
    static void register(Class<?> implClass, Class<?> lambdaType, Class<?> lambdaClass, String agentArgs) {
        var instance = instance();
        var included = instance.isIncluded(implClass, lambdaType, agentArgs);
        if (Boolean.getBoolean("lambdaagent.debug")) {
            System.out.println("~~~ " + (included
                ? "including"
                : "skipping") + " lambda " + implClass + "@" + lambdaType + " => " + lambdaClass);
        }
        if (!included) {
            return;
        }
        StackWalker.getInstance().walk(s -> s
            .skip(6)
            .dropWhile(f -> f.getClassName().startsWith("java") 
                || f.getClassName().startsWith("net.bytebuddy"))
            .findFirst())
            .ifPresent(f -> instance.traces.put(key(lambdaClass), Map.entry(f.toString(), f)));
        
    }
    
    private boolean isIncluded(Class<?> owner, Class<?> lambda, String args) {
        var includesOwner = Stream.concat(packageIncludes.stream(),
                argLinePackageIncludes.computeIfAbsent(args, this::parseIncludes).stream())
            .anyMatch(owner.getName()::startsWith);
        var includesLambda = lambdaIncludes.contains(lambda.getName());
        return includesOwner && includesLambda;
    }
    
    private static TraceKey key(Class<?> lambdaClass) {
        try {
            var lambda = lambdaClass.getName();
            var owner = lambdaClass.getName().substring(0, lambda.indexOf(LAMBDA_PREFIX));
            var classLoader = lambdaClass.getClassLoader();
            return new TraceKey(classLoader.loadClass(owner), lambda.substring(owner.length()));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Properties load(URL url) throws IOException {
        var props = new Properties();
        try (var is = url.openStream()) {
            props.load(is); 
        }
        return props;
    }

    private Set<String> parseIncludes(String configuration) {
        return Optional.ofNullable(configuration).stream()
            .flatMap(c -> Arrays.stream(c.split(",")))
            .filter(not(String::isEmpty))
            .collect(Collectors.toUnmodifiableSet());
    }
    
    private Object merge(Object one, Object other) {
        if (one == null) {
            return other;
        }
        if (other == null) {
            return one;
        }
        return one + "," + other;
    }

    /**
     * Cache key for storing lambda traces
     * 
     * @author azukovskij
     *
     */
    private static class TraceKey {
        
        private final  Class<?> owner;
        private final String lambdaId;
        
        private TraceKey(Class<?> owner, String lambdaId) {
            this.owner = owner;
            this.lambdaId = lambdaId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(lambdaId, owner);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TraceKey)) {
                return false;
            }
            TraceKey other = (TraceKey) obj;
            return Objects.equals(lambdaId, other.lambdaId) && Objects.equals(owner, other.owner);
        }
        
    }
    
}

package com.eisgroup.javaagent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.StackWalker.StackFrame;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Resolves the creation site of a lambda class that was not recorded when the class was spun, e.g. lambda classes
 * a JDK AOT cache ({@code -XX:AOTCache}) links at JVM start, or lambdas created before the agent attached.
 * <p>
 * The implementation method is read from the lambda class constant pool (JDK internal API), the call site from the
 * {@code invokedynamic} instructions of the class that declares the lambda. When the same method reference is used
 * several times in one class with the same functional interface and captured types, the first call site is reported.
 * <p>
 * If the JDK internal API is not available, a warning is printed once and only spun lambdas are traced.
 *
 * @author azukovskij
 */
final class StaticLambdaSites {

    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final Map<Class<?>, List<Site>> SITES = new ConcurrentHashMap<>();
    private static volatile ConstantPoolReader reader;

    private StaticLambdaSites() {
    }

    /**
     * Opens the JDK internal constant pool API to the agent. On failure prints a warning and leaves static resolution
     * disabled.
     *
     * @param instrumentation agent instrumentation
     */
    static void open(Instrumentation instrumentation) {
        try {
            reader = new ConstantPoolReader(instrumentation);
        } catch (Throwable e) {
            System.err.println("WARNING: lambda-javaagent: lambdas linked before the agent started (e.g. from a JDK AOT"
                + " cache) will have no trace, JDK internal constant pool API is not available: " + e);
        }
    }

    /**
     * @param lambdaClass lambda class
     * @param owner class that declares the lambda
     * @return the lambda call site, empty if it can't be resolved
     */
    static Optional<Site> resolve(Class<?> lambdaClass, Class<?> owner) {
        var cp = reader;
        if (cp == null) {
            return Optional.empty();
        }
        try {
            var refs = cp.memberRefs(lambdaClass);
            var interfaces = Arrays.stream(lambdaClass.getInterfaces())
                .map(Type::getInternalName)
                .collect(Collectors.toSet());
            var captured = Arrays.stream(lambdaClass.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(f -> Type.getDescriptor(f.getType()))
                .sorted()
                .collect(Collectors.toList());
            return SITES.computeIfAbsent(owner, StaticLambdaSites::index).stream()
                .filter(site -> refs.contains(site.impl)
                    && interfaces.contains(site.lambdaType)
                    && site.captured.equals(captured))
                .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<Site> index(Class<?> owner) {
        var sites = new ArrayList<Site>();
        var loader = owner.getClassLoader();
        var resource = owner.getName().replace('.', '/') + ".class";
        try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource)) {
            if (in == null) {
                return sites;
            }
            var file = new String[1];
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitSource(String source, String debug) {
                    file[0] = source;
                }

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                                                 String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private int line = -1;

                        @Override
                        public void visitLineNumber(int lineNumber, Label start) {
                            line = lineNumber;
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String indyDescriptor, Handle bootstrap,
                                                           Object... arguments) {
                            if (METAFACTORY.equals(bootstrap.getOwner()) && arguments.length > 1
                                    && arguments[1] instanceof Handle) {
                                var impl = (Handle) arguments[1];
                                var type = Type.getMethodType(indyDescriptor);
                                var captured = Arrays.stream(type.getArgumentTypes())
                                    .map(Type::getDescriptor)
                                    .sorted()
                                    .collect(Collectors.toList());
                                sites.add(new Site(impl, type.getReturnType().getInternalName(), captured,
                                    new SiteFrame(owner, method, file[0], line)));
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException e) {
            // no call sites: lambdas of this class get no trace
        }
        return sites;
    }

    /**
     * Lambda call site found in the bytecode of the declaring class.
     */
    static final class Site {
        private final String impl;
        private final String implClass;
        private final String lambdaType;
        private final List<String> captured;
        private final SiteFrame frame;

        private Site(Handle impl, String lambdaType, List<String> captured, SiteFrame frame) {
            this.impl = impl.getOwner() + '.' + impl.getName() + impl.getDesc();
            this.implClass = impl.getOwner().replace('/', '.');
            this.lambdaType = lambdaType;
            this.captured = captured;
            this.frame = frame;
        }

        /**
         * @return binary name of the class that declares the lambda implementation method
         */
        String implClass() {
            return implClass;
        }

        /**
         * @return binary name of the functional interface
         */
        String lambdaType() {
            return lambdaType.replace('/', '.');
        }

        /**
         * @return frame of the lambda call site
         */
        StackFrame frame() {
            return frame;
        }
    }

    /**
     * Stack frame of a lambda call site resolved from bytecode; the bytecode index is not known.
     */
    private static final class SiteFrame implements StackFrame {
        private final Class<?> declaringClass;
        private final String methodName;
        private final String fileName;
        private final int lineNumber;

        private SiteFrame(Class<?> declaringClass, String methodName, String fileName, int lineNumber) {
            this.declaringClass = declaringClass;
            this.methodName = methodName;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }

        @Override
        public String getClassName() {
            return declaringClass.getName();
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public Class<?> getDeclaringClass() {
            return declaringClass;
        }

        @Override
        public int getByteCodeIndex() {
            return -1;
        }

        @Override
        public String getFileName() {
            return fileName;
        }

        @Override
        public int getLineNumber() {
            return lineNumber;
        }

        @Override
        public boolean isNativeMethod() {
            return false;
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            return new StackTraceElement(getClassName(), methodName, fileName, lineNumber);
        }

        @Override
        public String toString() {
            return toStackTraceElement().toString();
        }
    }

    /**
     * Reflective access to {@code SharedSecrets.getJavaLangAccess().getConstantPool(Class)}, validated on creation.
     */
    private static final class ConstantPoolReader {
        private static final String[] SHARED_SECRETS = {
            "jdk.internal.access.SharedSecrets", // JDK 12+
            "jdk.internal.misc.SharedSecrets"    // JDK 9-11
        };

        private final Object javaLangAccess;
        private final Method getConstantPool;
        private final Method getSize;
        private final Method getMemberRefInfoAt;

        private ConstantPoolReader(Instrumentation instrumentation) throws ReflectiveOperationException {
            var sharedSecrets = sharedSecrets();
            var constantPool = Class.forName("jdk.internal.reflect.ConstantPool");
            var agent = StaticLambdaSites.class.getModule();
            instrumentation.redefineModule(Object.class.getModule(), Set.of(),
                Map.of(sharedSecrets.getPackageName(), Set.of(agent), constantPool.getPackageName(), Set.of(agent)),
                Map.of(), Set.of(), Map.of());
            javaLangAccess = sharedSecrets.getMethod("getJavaLangAccess").invoke(null);
            getConstantPool = getConstantPoolMethod(javaLangAccess.getClass());
            getSize = constantPool.getMethod("getSize");
            getMemberRefInfoAt = constantPool.getMethod("getMemberRefInfoAt", int.class);
            if (memberRefs(ConstantPoolReader.class).isEmpty()) {
                throw new IllegalStateException("no member references read from the constant pool");
            }
        }

        private static Class<?> sharedSecrets() throws ClassNotFoundException {
            for (var name : SHARED_SECRETS) {
                try {
                    return Class.forName(name);
                } catch (ClassNotFoundException e) {
                    // try the next location
                }
            }
            throw new ClassNotFoundException(String.join(", ", SHARED_SECRETS));
        }

        private static Method getConstantPoolMethod(Class<?> javaLangAccess) throws NoSuchMethodException {
            for (var type : javaLangAccess.getInterfaces()) {
                try {
                    return type.getMethod("getConstantPool", Class.class);
                } catch (NoSuchMethodException e) {
                    // not this interface
                }
            }
            throw new NoSuchMethodException("JavaLangAccess.getConstantPool(Class)");
        }

        private Set<String> memberRefs(Class<?> type) throws ReflectiveOperationException {
            var cp = getConstantPool.invoke(javaLangAccess, type);
            var size = (int) getSize.invoke(cp);
            var refs = new HashSet<String>();
            for (var i = 1; i < size; i++) {
                try {
                    var ref = (String[]) getMemberRefInfoAt.invoke(cp, i);
                    refs.add(ref[0].replace('.', '/') + '.' + ref[1] + ref[2]);
                } catch (InvocationTargetException e) {
                    // not a member reference
                }
            }
            return refs;
        }
    }
}

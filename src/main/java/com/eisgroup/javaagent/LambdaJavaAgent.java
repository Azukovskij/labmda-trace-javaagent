package com.eisgroup.javaagent;

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.StackWalker.StackFrame;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;

/**
 * Java agent that instruments lambda creation to provide creation {@link StackFrame} by lambda class
 * 
 * @author azukovskij
 *
 */
public class LambdaJavaAgent {

    private static final String NULL_ARGS = "";
    
    public static void attach() {
        agentmain(null, ByteBuddyAgent.install());
    }
    @SuppressWarnings("UnusedDeclaration")
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrument(agentArgs, instrumentation);   
    }

    @SuppressWarnings("UnusedDeclaration")
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        instrument(agentArgs, instrumentation);   
    }
    
    private static void instrument(String agentArgs, Instrumentation instrumentation) {
        try {
            var byteBuddy = new ByteBuddy();
            var factory = Class.forName("java.lang.invoke.InnerClassLambdaMetafactory");
            byteBuddy.redefine(factory)
                .visit(Advice
                    .withCustomMapping()
                    .bind(AgentArgs.class, Optional.ofNullable(agentArgs)
                        .orElse(NULL_ARGS))
                    .to(LambdaJavaAgent.Interceptor.class).on(named("spinInnerClass")))
                .make()
                .load(factory.getClassLoader(), ClassReloadingStrategy.of(instrumentation));
            
        } catch (Exception ignored) {
            ignored.printStackTrace();
            return;
        }
     
    }

    /**
     * Marker annotation for passing javaagent configuration to bytebuddy 
     * 
     * @author azukovskij
     *
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AgentArgs {}
    

    /**
     * Byte buddy interceptor that invokes {@link LambdaTracer#register(Class, Class, String, String)} on lambda class init  
     * 
     * @author azukovskij
     *
     */
    public static class Interceptor {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void intercept(
                @Advice.This Object source,
                @Advice.FieldValue(value = "implClass") Class<?> implClass,
                @Advice.Return Class<?> lambdaClass,
                @AgentArgs String agentArgs) throws Exception {
            var callerName = implClass.getName();
            if (callerName.startsWith("com.eisgroup.javaagent.LambdaTracer")  // recursion
                     || callerName.startsWith("java.") || callerName.startsWith("jdk.") || callerName.startsWith("sun.")) {
                return;
            }
            var f = (Field) null;
            var factoryClazz = source.getClass().getSuperclass();
            try {
                f = factoryClazz.getDeclaredField("samBase");
                f.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                f = factoryClazz.getDeclaredField("interfaceClass");
                f.setAccessible(true);
            }
            var samBase = (Class<?>) f.get(source);
            var props = System.getProperties();
            var method = (Method) props.get("LambdaTracer$RegisterMethod");
            if (method == null) {
                var clazz = Class.forName("com.eisgroup.javaagent.LambdaTracer", true, ClassLoader.getSystemClassLoader());
                method = clazz.getDeclaredMethod("register", Class.class, Class.class, Class.class, String.class);
                method.setAccessible(true);
                props.putIfAbsent("LambdaTracer$RegisterMethod", method);
            }
            method.invoke(null, implClass, samBase, lambdaClass, agentArgs);
        }
        
    }
    
    
}
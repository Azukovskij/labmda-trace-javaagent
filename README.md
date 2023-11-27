# Lambda Tracing Java Agent

Java agent that records stack frames for lambda expression, that allows to trace lambda class to a source code line. 
As all instrumentation happens in labmda class initialisation - this javaagent has has no performance overhead after warmup (with all classes initialised), except ~80 bytes per lambda memory usage. 


## Package Includes Cofiguration
In order for labmda to be traceable owner class package needs to be included into javaagent configuration. This can be done
via javaagent arguments or by creating `lambdaagent.properties` file within application claspath.

In order to configure package includes via javaagnet arguments simply pass comma separate package names to `-javaagent` option, e.g.
```
-javaagent:/path/to/lambda-javaagent.jar:com.package.a,com.package.b
```

Alternatively create `lambdaagent.properties` in application path and pass `package.includes`, e.g.
```
package.includes=com.package.a,com.package.b
```

### Including custom functional interfaces
By default configuration javaagent traces only labmdas that are subtype of:
* java.lang.Runnable
* java.util.concurrent.Callable
* java.util.function.Function
* java.util.function.Supplier
* java.util.function.Predicate
* java.util.function.Consumer
* java.util.function.BooleanSupplier
* java.util.function.BiConsumer
* java.util.function.BiFunction
* java.util.function.BiPredicate
* java.util.function.LongConsumer
* java.util.function.IntFunction
* io.reactivex.functions.Action
* io.reactivex.functions.BiConsumer
* io.reactivex.functions.BiFunction
* io.reactivex.functions.BiPredicate
* io.reactivex.functions.BooleanSupplier
* io.reactivex.functions.Cancellable
* io.reactivex.functions.Consumer
* io.reactivex.functions.Function, io.reactivex.functions.Function3, io.reactivex.functions.Function4, io.reactivex.functions.Function5, io.reactivex.functions.Function6, io.reactivex.functions.Function7, io.reactivex.functions.Function8,  io.reactivex.functions.Function9, 
* io.reactivex.functions.IntFunction
* io.reactivex.functions.LongConsumer
* io.reactivex.functions.Predicate

In case other functional interfaces are required to be traced, please add comma separated class names in `lambda.includes` property of `lambdaagent.properties` file, e.g.
```
lambda.includes=com.my.CustomFunc1,com.my.CustomFunc2
```

### Verbose Logging
Lambda includes can configured to be sent to system out using `labmdaagent.debug` system property, e.g.:
```
-Dlabmdaagent.debug=true
```
will produce `included` or `excluded` message upon lambda class initialisation:
```
~~~ including/skipping lambda com.mypackage.MyClass@java.util.Predicate" + lambdaType + " => ..$$Lambda$1");
~~~ skipping lambda com.excluded.ExcludedClass@java.util.Predicate" + lambdaType + " => ..$$Lambda$2");
```

## Usage example

In order to collect exception count JMX metrics, attach javaagent (from releases section) to java application:

```bash
-javaagent:/path/to/lambda-javaagent.jar:com.mypackage
```

After javaagent is attached lambda classes can be traced by invoking `LambdaTracer`, e.g. via reflections:
```
   var clazz = Class.forName("com.eisgroup.javaagent.LambdaTracer");
   var instance = clazz.getDeclaredMethod("instance").invoke(null);
   var method = clazz.getDeclaredMethod("trace", Class.class);
   return method.invoke(instance, lambdaClazz);
```

package nl.nextend.filenet.cews.agent;

import java.lang.instrument.Instrumentation;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Entry point for the CEWS request capture javaagent.
 *
 * <p>The agent installs Byte Buddy advices on top of the servlet request
 * lifecycle so request metadata and a bounded body preview can be captured
 * without modifying the hosted application.</p>
 *
 * <p>The runtime state is published as one immutable snapshot to avoid readers
 * observing a partially initialized pair of config and writer references.</p>
 */
public final class RequestCaptureAgent {
    private static final String AGENT_PACKAGE_PREFIX = "nl.nextend.filenet.cews.agent.";
    private static final String BYTE_BUDDY_PACKAGE_PREFIX = "net.bytebuddy.";
    private static final String FILTER_CLASS = "javax.servlet.Filter";
    private static final String SERVLET_CLASS = "javax.servlet.Servlet";
    private static final String SERVLET_INPUT_STREAM_CLASS = "javax.servlet.ServletInputStream";
    private static final String SERVLET_REQUEST_CLASS = "javax.servlet.ServletRequest";
    private static final String SERVLET_RESPONSE_CLASS = "javax.servlet.ServletResponse";
    private static final String HTTP_SERVLET_REQUEST_CLASS = "javax.servlet.http.HttpServletRequest";
    private static final String HTTP_SERVLET_RESPONSE_CLASS = "javax.servlet.http.HttpServletResponse";
    private static final String AGENT_INSTALLED_PHASE = "agent-installed";
    private static final String AGENT_TRANSFORM_PHASE = "agent-transform";

    private static final AtomicReference<AgentRuntime> RUNTIME = new AtomicReference<>();

    private RequestCaptureAgent() {
    }

    /**
     * JVM entry point used when the agent is attached during process startup.
     *
     * @param agentArgs agent arguments supplied in the {@code -javaagent} option
     * @param instrumentation JVM instrumentation handle used to install bytecode hooks
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation);
    }

    /**
     * JVM entry point used when the agent is attached to an already running JVM.
     *
     * @param agentArgs agent arguments supplied by the attach caller
     * @param instrumentation JVM instrumentation handle used to install bytecode hooks
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation);
    }

    /**
     * Returns the active capture configuration, or {@code null} if the agent has
     * not been initialized yet.
     */
    static RequestCaptureConfig config() {
        AgentRuntime runtime = runtime();
        return runtime == null ? null : runtime.config;
    }

    /**
     * Returns the active asynchronous event writer, or {@code null} if the agent
     * has not been initialized yet.
     */
    static AsyncEventWriter writer() {
        AgentRuntime runtime = runtime();
        return runtime == null ? null : runtime.writer;
    }

    /**
     * Performs one-time agent installation.
     *
     * <p>This method is synchronized because the JVM may attempt to attach the
     * agent from more than one entry path. The method exits early if another
     * caller has already finished the initialization.</p>
     */
    private static synchronized void install(String agentArgs, Instrumentation instrumentation) {
        if (runtime() != null) {
            return;
        }
        AgentRuntime runtime = createRuntime(agentArgs);
        RUNTIME.set(runtime);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime.writer::close, "request-capture-shutdown"));

        buildAgentBuilder().installOn(instrumentation);
        runtime.writer.enqueue(buildInstalledEvent(runtime.config));
    }

    private static AgentRuntime runtime() {
        return RUNTIME.get();
    }

    private static AgentRuntime createRuntime(String agentArgs) {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(agentArgs);
        AsyncEventWriter writer = new AsyncEventWriter(config.outputFile(), config.queueCapacity());
        return new AgentRuntime(config, writer);
    }

    private static AgentBuilder buildAgentBuilder() {
        AgentBuilder requestLifecycleBuilder = new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith(BYTE_BUDDY_PACKAGE_PREFIX)
                .or(ElementMatchers.nameStartsWith(AGENT_PACKAGE_PREFIX)));

        RequestCaptureConfig currentConfig = config();
        AsyncEventWriter currentWriter = writer();
        if (currentConfig != null && currentConfig.diagnosticTransforms()) {
            requestLifecycleBuilder = requestLifecycleBuilder.with(new TransformationLoggingListener(currentWriter));
        }

        requestLifecycleBuilder = requestLifecycleBuilder
            .type(ElementMatchers.hasSuperType(ElementMatchers.named(SERVLET_CLASS))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract())))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(ServletServiceAdvice.class)
                    .on(buildServletServiceMatcher())));

        AgentBuilder filterBuilder = requestLifecycleBuilder
            .type(ElementMatchers.hasSuperType(ElementMatchers.named(FILTER_CLASS))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract())))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(FilterDoFilterAdvice.class)
                    .on(buildFilterDoFilterMatcher())));

        return filterBuilder
            .type(ElementMatchers.named(SERVLET_INPUT_STREAM_CLASS))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder
                    .visit(Advice.to(ServletInputStreamAdvice.class)
                        .on(ElementMatchers.named("read")
                            .and(ElementMatchers.takesArguments(0))
                            .and(ElementMatchers.returns(int.class))))
                    .visit(Advice.to(ServletInputStreamFullArrayReadAdvice.class)
                        .on(ElementMatchers.named("read")
                            .and(ElementMatchers.takesArguments(byte[].class))
                            .and(ElementMatchers.returns(int.class))))
                    .visit(Advice.to(ServletInputStreamArrayReadAdvice.class)
                        .on(ElementMatchers.named("read")
                            .and(ElementMatchers.takesArguments(byte[].class, int.class, int.class))
                            .and(ElementMatchers.returns(int.class)))));
    }

    static ElementMatcher.Junction<MethodDescription> buildServletServiceMatcher() {
        ElementMatcher.Junction<MethodDescription> genericService = ElementMatchers.named("service")
            .and(ElementMatchers.takesArguments(2))
            .and(ElementMatchers.takesArgument(0, ElementMatchers.named(SERVLET_REQUEST_CLASS)))
            .and(ElementMatchers.takesArgument(1, ElementMatchers.named(SERVLET_RESPONSE_CLASS)));

        ElementMatcher.Junction<MethodDescription> httpService = ElementMatchers.named("service")
            .and(ElementMatchers.takesArguments(2))
            .and(ElementMatchers.takesArgument(0, ElementMatchers.named(HTTP_SERVLET_REQUEST_CLASS)))
            .and(ElementMatchers.takesArgument(1, ElementMatchers.named(HTTP_SERVLET_RESPONSE_CLASS)));

        return genericService.or(httpService);
    }

    static ElementMatcher.Junction<MethodDescription> buildFilterDoFilterMatcher() {
        return ElementMatchers.named("doFilter")
            .and(ElementMatchers.takesArguments(3))
            .and(ElementMatchers.takesArgument(0, ElementMatchers.named(SERVLET_REQUEST_CLASS)))
            .and(ElementMatchers.takesArgument(1, ElementMatchers.named(SERVLET_RESPONSE_CLASS)));
    }

    private static String buildInstalledEvent(RequestCaptureConfig config) {
        return "{\"timestamp\":\""
            + Instant.now().toString()
            + "\",\"phase\":\""
            + AGENT_INSTALLED_PHASE
            + "\",\"output\":"
            + CaptureContext.jsonQuote(config.outputFile().getAbsolutePath())
            + "}";
    }

    private static final class TransformationLoggingListener extends AgentBuilder.Listener.Adapter {
        private final AsyncEventWriter writer;
        private final Set<String> seenTypes = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        private TransformationLoggingListener(AsyncEventWriter writer) {
            this.writer = writer;
        }

        @Override
        public void onTransformation(TypeDescription typeDescription,
                                     ClassLoader classLoader,
                                     JavaModule module,
                                     boolean loaded,
                                     DynamicType dynamicType) {
            if (writer == null || typeDescription == null) {
                return;
            }
            String typeName = typeDescription.getName();
            if (seenTypes.add(typeName)) {
                writer.enqueue(buildTransformationEvent(typeName));
            }
        }

        private static String buildTransformationEvent(String typeName) {
            return "{\"timestamp\":\""
                + Instant.now().toString()
                + "\",\"phase\":\""
                + AGENT_TRANSFORM_PHASE
                + "\",\"type\":"
                + CaptureContext.jsonQuote(typeName)
                + "}";
        }
    }

    /**
     * Immutable holder for all state that needs to be shared by Byte Buddy advice
     * callbacks after installation has completed.
     */
    private static final class AgentRuntime {
        private final RequestCaptureConfig config;
        private final AsyncEventWriter writer;

        private AgentRuntime(RequestCaptureConfig config, AsyncEventWriter writer) {
            this.config = config;
            this.writer = writer;
        }
    }
}

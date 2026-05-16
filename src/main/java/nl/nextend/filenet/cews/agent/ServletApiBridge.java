package nl.nextend.filenet.cews.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;

public final class ServletApiBridge {
    private static final String ASYNC_CONTEXT_CLASS = "javax.servlet.AsyncContext";
    private static final String ASYNC_LISTENER_CLASS = "javax.servlet.AsyncListener";
    private static final String ASYNC_EVENT_CLASS = "javax.servlet.AsyncEvent";
    private static final String HTTP_SERVLET_REQUEST_CLASS = "javax.servlet.http.HttpServletRequest";
    private static final String HTTP_SERVLET_RESPONSE_CLASS = "javax.servlet.http.HttpServletResponse";
    private static final String SERVLET_REQUEST_CLASS = "javax.servlet.ServletRequest";
    private static final String SERVLET_RESPONSE_CLASS = "javax.servlet.ServletResponse";

    private ServletApiBridge() {
    }

    static String requestUri(Object request) {
        return invokeString(request, HTTP_SERVLET_REQUEST_CLASS, "getRequestURI");
    }

    static String requestMethod(Object request) {
        return invokeString(request, HTTP_SERVLET_REQUEST_CLASS, "getMethod");
    }

    static String queryString(Object request) {
        return invokeString(request, HTTP_SERVLET_REQUEST_CLASS, "getQueryString");
    }

    static String remoteAddress(Object request) {
        return invokeString(request, SERVLET_REQUEST_CLASS, "getRemoteAddr");
    }

    static String contentType(Object request) {
        return invokeString(request, SERVLET_REQUEST_CLASS, "getContentType");
    }

    static long contentLength(Object request) {
        Object length = invoke(request, SERVLET_REQUEST_CLASS, "getContentLengthLong");
        if (length instanceof Long) {
            return ((Long) length).longValue();
        }
        Object intLength = invoke(request, SERVLET_REQUEST_CLASS, "getContentLength");
        return intLength instanceof Integer ? ((Integer) intLength).longValue() : -1L;
    }

    static Enumeration<String> headerNames(Object request) {
        Object headerNames = invoke(request, HTTP_SERVLET_REQUEST_CLASS, "getHeaderNames");
        if (headerNames instanceof Enumeration) {
            @SuppressWarnings("unchecked")
            Enumeration<String> typed = (Enumeration<String>) headerNames;
            return typed;
        }
        return Collections.emptyEnumeration();
    }

    static String header(Object request, String headerName) {
        Object value = invoke(request, HTTP_SERVLET_REQUEST_CLASS, "getHeader", new Class<?>[] {String.class}, new Object[] {headerName});
        return value instanceof String ? (String) value : null;
    }

    static Object getAttribute(Object request, String attributeName) {
        return invoke(request, SERVLET_REQUEST_CLASS, "getAttribute", new Class<?>[] {String.class}, new Object[] {attributeName});
    }

    static void setAttribute(Object request, String attributeName, Object value) {
        invoke(request, SERVLET_REQUEST_CLASS, "setAttribute", new Class<?>[] {String.class, Object.class}, new Object[] {attributeName, value});
    }

    static void removeAttribute(Object request, String attributeName) {
        invoke(request, SERVLET_REQUEST_CLASS, "removeAttribute", new Class<?>[] {String.class}, new Object[] {attributeName});
    }

    static boolean isAsyncStarted(Object request) {
        Object asyncStarted = invoke(request, SERVLET_REQUEST_CLASS, "isAsyncStarted");
        return asyncStarted instanceof Boolean && ((Boolean) asyncStarted).booleanValue();
    }

    static Object asyncContext(Object request) {
        return invoke(request, SERVLET_REQUEST_CLASS, "getAsyncContext");
    }

    static int responseStatus(Object response) {
        Object status = invoke(response, HTTP_SERVLET_RESPONSE_CLASS, "getStatus");
        return status instanceof Integer ? ((Integer) status).intValue() : -1;
    }

    public static void setResponseHeader(Object response, String headerName, String headerValue) {
        invoke(response,
            HTTP_SERVLET_RESPONSE_CLASS,
            "setHeader",
            new Class<?>[] {String.class, String.class},
            new Object[] {headerName, headerValue});
    }

    static Throwable asyncThrowable(Object asyncEvent) {
        Object throwable = invoke(asyncEvent, ASYNC_EVENT_CLASS, "getThrowable");
        return throwable instanceof Throwable ? (Throwable) throwable : null;
    }

    static Object suppliedRequest(Object asyncEvent) {
        return invoke(asyncEvent, ASYNC_EVENT_CLASS, "getSuppliedRequest");
    }

    static Object suppliedResponse(Object asyncEvent) {
        return invoke(asyncEvent, ASYNC_EVENT_CLASS, "getSuppliedResponse");
    }

    static Object asyncContextFromEvent(Object asyncEvent) {
        return invoke(asyncEvent, ASYNC_EVENT_CLASS, "getAsyncContext");
    }

    static void addAsyncListener(Object asyncContext,
                                 Object request,
                                 Object response,
                                 AsyncCallbacks callbacks) {
        if (asyncContext == null || callbacks == null) {
            return;
        }
        Class<?> asyncListenerType = loadType(asyncContext.getClass().getClassLoader(), ASYNC_LISTENER_CLASS);
        if (asyncListenerType == null) {
            return;
        }
        Object listener = Proxy.newProxyInstance(
            asyncListenerType.getClassLoader(),
            new Class<?>[] {asyncListenerType},
            new AsyncListenerInvocationHandler(callbacks));

        Class<?> requestType = loadType(asyncContext.getClass().getClassLoader(), SERVLET_REQUEST_CLASS);
        Class<?> responseType = loadType(asyncContext.getClass().getClassLoader(), SERVLET_RESPONSE_CLASS);
        if (request != null && response != null && requestType != null && responseType != null) {
            invoke(asyncContext,
                ASYNC_CONTEXT_CLASS,
                "addListener",
                new Class<?>[] {asyncListenerType, requestType, responseType},
                new Object[] {listener, request, response});
            return;
        }
        invoke(asyncContext, ASYNC_CONTEXT_CLASS, "addListener", new Class<?>[] {asyncListenerType}, new Object[] {listener});
    }

    interface AsyncCallbacks {
        void onComplete(Object asyncEvent);

        void onTimeout(Object asyncEvent);

        void onError(Object asyncEvent);
    }

    private static final class AsyncListenerInvocationHandler implements InvocationHandler {
        private final AsyncCallbacks callbacks;

        private AsyncListenerInvocationHandler(AsyncCallbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            Object asyncEvent = args != null && args.length > 0 ? args[0] : null;
            if ("onComplete".equals(methodName)) {
                callbacks.onComplete(asyncEvent);
                return null;
            }
            if ("onTimeout".equals(methodName)) {
                callbacks.onTimeout(asyncEvent);
                return null;
            }
            if ("onError".equals(methodName)) {
                callbacks.onError(asyncEvent);
                return null;
            }
            if ("onStartAsync".equals(methodName)) {
                addAsyncListener(asyncContextFromEvent(asyncEvent), suppliedRequest(asyncEvent), suppliedResponse(asyncEvent), callbacks);
                return null;
            }
            if ("toString".equals(methodName)) {
                return AsyncListenerInvocationHandler.class.getSimpleName();
            }
            return null;
        }
    }

    private static String invokeString(Object target, String interfaceName, String methodName) {
        Object value = invoke(target, interfaceName, methodName);
        return value instanceof String ? (String) value : null;
    }

    private static Object invoke(Object target, String interfaceName, String methodName) {
        return invoke(target, interfaceName, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target,
                                 String interfaceName,
                                 String methodName,
                                 Class<?>[] parameterTypes,
                                 Object[] arguments) {
        if (target == null) {
            return null;
        }
        try {
            Class<?> interfaceType = loadType(target.getClass().getClassLoader(), interfaceName);
            if (interfaceType == null) {
                return null;
            }
            Method method = interfaceType.getMethod(methodName, parameterTypes);
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | IllegalArgumentException reflectiveOperationException) {
            return null;
        }
    }

    private static Class<?> loadType(ClassLoader classLoader, String typeName) {
        try {
            if (classLoader != null) {
                return Class.forName(typeName, false, classLoader);
            }
            return Class.forName(typeName);
        } catch (ClassNotFoundException classNotFoundException) {
            return null;
        }
    }
}
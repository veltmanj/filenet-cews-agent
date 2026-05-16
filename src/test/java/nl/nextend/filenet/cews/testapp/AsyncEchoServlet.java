package nl.nextend.filenet.cews.testapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class AsyncEchoServlet extends HttpServlet {
    private static final long ASYNC_DELAY_MILLIS = 150L;
    private static final String REQUEST_BODY_ATTRIBUTE = AsyncEchoServlet.class.getName() + ".requestBody";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("async:" + request.getAttribute(REQUEST_BODY_ATTRIBUTE));
            return;
        }

        request.setAttribute(REQUEST_BODY_ATTRIBUTE, readRequestBody(request));

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(TimeUnit.SECONDS.toMillis(5));
        asyncContext.start(new Runnable() {
            @Override
            public void run() {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ASYNC_DELAY_MILLIS));
                asyncContext.dispatch();
            }
        });
    }

    private static String readRequestBody(HttpServletRequest request) throws IOException {
        try (InputStream inputStream = request.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

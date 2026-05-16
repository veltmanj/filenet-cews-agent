package nl.nextend.filenet.cews.agent.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class LibertyWithAgentContainer extends GenericContainer<LibertyWithAgentContainer> {
    private static final DockerImageName LIBERTY_IMAGE =
        DockerImageName.parse("icr.io/appcafe/open-liberty:full-java8-openj9-ubi-minimal");
    private static final String CONTAINER_AGENT_PATH = "/opt/agents/filenet-cews-agent.jar";
    private static final String CONTAINER_EVENT_PATH = "/tmp/request-capture.ndjson";

    public LibertyWithAgentContainer(String instanceName,
                                     Path warFile,
                                     Path agentJar,
                                     String agentArgs) throws IOException {
        super(LIBERTY_IMAGE);

        withExposedPorts(9080);
        withCopyFileToContainer(MountableFile.forClasspathResource("liberty/server.xml"), "/config/server.xml");
        withCopyFileToContainer(MountableFile.forHostPath(warFile), "/config/dropins/capture.war");
        withCopyFileToContainer(MountableFile.forHostPath(agentJar), CONTAINER_AGENT_PATH);
        withEnv("JAVA_TOOL_OPTIONS", "-javaagent:" + CONTAINER_AGENT_PATH + "=" + agentArgsWithOutput(agentArgs));
        withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("liberty." + instanceName)).withPrefix(instanceName));
        waitingFor(Wait.forHttp("/capture/health").forPort(9080).forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(3)));
    }

    public String endpoint(String path) {
        return "http://" + getHost() + ":" + getMappedPort(9080) + path;
    }

    public List<String> readEventLines() throws IOException {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                String contents = copyFileFromContainer(CONTAINER_EVENT_PATH, LibertyWithAgentContainer::readUtf8);
                List<String> lines = splitLines(contents);
                if (!lines.isEmpty()) {
                    return lines;
                }
            } catch (RuntimeException runtimeException) {
                lastFailure = runtimeException;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250L));
        }
        if (lastFailure != null) {
            throw new IOException("Unable to read request capture events from container", lastFailure);
        }
        return new ArrayList<String>();
    }

    private static String readUtf8(InputStream inputStream) throws IOException {
        byte[] bytes = new byte[8192];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = inputStream.read(bytes)) >= 0) {
            builder.append(new String(bytes, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static List<String> splitLines(String contents) {
        List<String> lines = new ArrayList<String>();
        for (String line : contents.split("\\R")) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String agentArgsWithOutput(String agentArgs) {
        return "output=" + CONTAINER_EVENT_PATH + "," + agentArgs;
    }
}
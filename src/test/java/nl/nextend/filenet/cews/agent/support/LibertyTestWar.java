package nl.nextend.filenet.cews.agent.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import nl.nextend.filenet.cews.testapp.AsyncEchoServlet;
import nl.nextend.filenet.cews.testapp.HealthServlet;
import nl.nextend.filenet.cews.testapp.SyncEchoServlet;

public final class LibertyTestWar {
    private static final String WEB_XML = "testapp/WEB-INF/web.xml";

    private LibertyTestWar() {
    }

    public static Path build(Path warPath) throws IOException {
        Files.createDirectories(warPath.getParent());
        try (OutputStream outputStream = Files.newOutputStream(warPath);
             JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
            addWebXml(jarOutputStream);
            addClass(jarOutputStream, SyncEchoServlet.class);
            addClass(jarOutputStream, AsyncEchoServlet.class);
            addClass(jarOutputStream, HealthServlet.class);
        }
        return warPath;
    }

    private static void addWebXml(JarOutputStream jarOutputStream) throws IOException {
        addResource(jarOutputStream, WEB_XML, "WEB-INF/web.xml");
    }

    private static void addClass(JarOutputStream jarOutputStream, Class<?> type) throws IOException {
        String classResource = type.getName().replace('.', '/') + ".class";
        addResource(jarOutputStream, classResource, "WEB-INF/classes/" + classResource);
        addCompanionClasses(jarOutputStream, type);
    }

    private static void addCompanionClasses(JarOutputStream jarOutputStream, Class<?> type) throws IOException {
        Path classDirectory = compiledClassDirectory(type);
        String simpleName = type.getSimpleName();
        try (DirectoryStream<Path> companions = Files.newDirectoryStream(classDirectory, simpleName + "$*.class")) {
            for (Path companion : companions) {
                String relativeResource = classDirectory.relativize(companion).toString();
                String packagePath = type.getPackage().getName().replace('.', '/');
                String classResource = packagePath + "/" + relativeResource;
                addResource(jarOutputStream, classResource, "WEB-INF/classes/" + classResource);
            }
        }
    }

    private static Path compiledClassDirectory(Class<?> type) throws IOException {
        try {
            Path classesRoot = Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            return classesRoot.resolve(type.getPackage().getName().replace('.', '/'));
        } catch (URISyntaxException uriSyntaxException) {
            throw new IOException("Unable to resolve compiled class directory for " + type.getName(), uriSyntaxException);
        }
    }

    private static void addResource(JarOutputStream jarOutputStream,
                                    String sourceResource,
                                    String targetEntry) throws IOException {
        JarEntry entry = new JarEntry(targetEntry);
        jarOutputStream.putNextEntry(entry);
        try (InputStream inputStream = resourceStream(sourceResource)) {
            copy(inputStream, jarOutputStream);
        }
        jarOutputStream.closeEntry();
    }

    private static InputStream resourceStream(String resource) throws IOException {
        InputStream inputStream = LibertyTestWar.class.getClassLoader().getResourceAsStream(resource);
        if (inputStream == null) {
            throw new IOException("Missing test resource: " + resource);
        }
        return inputStream;
    }

    private static void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
    }
}
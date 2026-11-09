package io.jsl.nyajsw.agent;

import static org.testng.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Guards the agent module's build contract (plan D2): main classes are Java 8 bytecode because they
 * are loaded into application JVMs as old as Java 8, while tests are compiled for and run on Java 21.
 */
public class AgentBuildSmokeTest {

    /** Class-file major version of Java 8. */
    private static final int JAVA_8_MAJOR = 52;

    /** A record only compiles when the tests target Java 16 or later, proving {@code testRelease}. */
    private record ClassFileVersion(Path file, int major) {
    }

    @Test
    public void testsRunOnJava21OrLater() {
        assertTrue(Runtime.version().feature() >= 21, "tests must run on Java 21+, got " + Runtime.version());
    }

    @Test
    public void mainClassesTargetJava8() throws IOException {
        String dir = System.getProperty("nyajsw.test.mainClassesDir");
        assertTrue(dir != null && !dir.isBlank(), "surefire must set nyajsw.test.mainClassesDir");
        Path mainClasses = Paths.get(dir);
        if (!Files.isDirectory(mainClasses)) {
            throw new SkipException("no agent main classes compiled yet");
        }
        List<ClassFileVersion> tooNew;
        try (Stream<Path> files = Files.walk(mainClasses)) {
            tooNew = files.filter(p -> p.toString().endsWith(".class"))
                    .map(AgentBuildSmokeTest::readVersion)
                    .filter(v -> v.major() > JAVA_8_MAJOR)
                    .toList();
        }
        assertTrue(tooNew.isEmpty(), "agent classes must be Java 8 bytecode: " + tooNew);
    }

    private static ClassFileVersion readVersion(Path classFile) {
        try (InputStream in = Files.newInputStream(classFile);
             DataInputStream data = new DataInputStream(in)) {
            data.readInt(); // magic
            data.readUnsignedShort(); // minor
            return new ClassFileVersion(classFile, data.readUnsignedShort());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + classFile, e);
        }
    }
}

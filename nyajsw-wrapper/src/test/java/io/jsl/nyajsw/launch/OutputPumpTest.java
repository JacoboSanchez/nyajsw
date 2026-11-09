package io.jsl.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.ConfigLoader;
import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.config.SecretMasker;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.log.WrapperLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

public class OutputPumpTest {

    private static final String SECRET = "SuperSecret123";

    /** A masker for a configuration whose service password is {@link #SECRET}. */
    private static SecretMasker secrets() {
        return masker("wrapper.ntservice.password=" + SECRET + "\n");
    }

    /** A masker for a configuration without secrets: lines are cut at exactly {@link OutputPump#MAX_LINE}. */
    private static SecretMasker noSecrets() {
        return masker("");
    }

    private static SecretMasker masker(String extra) {
        try {
            Path dir = Files.createTempDirectory("nyajsw-pump");
            Path conf = Files.writeString(dir.resolve("wrapper.conf"), "wrapper.java.app.mainclass=Main\n" + extra);
            SecretMasker masker = WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry())
                    .load(conf)).secrets();
            Files.delete(conf);
            Files.delete(dir);
            return masker;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> pump(byte[] bytes, Charset charset) {
        return pump(new ByteArrayInputStream(bytes), charset, noSecrets());
    }

    private static List<String> pumpWithSecret(byte[] bytes) {
        return pump(new ByteArrayInputStream(bytes), StandardCharsets.US_ASCII, secrets());
    }

    private static List<String> pump(InputStream in, Charset charset) {
        return pump(in, charset, noSecrets());
    }

    private static List<String> pump(InputStream in, Charset charset, SecretMasker masker) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WrapperLog log = WrapperLog.console(new PrintStream(out, true, StandardCharsets.UTF_8), LogLevel.INFO)) {
            new OutputPump(in, charset, log.forSource("jvm 1"), LogLevel.INFO, masker).run();
        }
        return out.toString(StandardCharsets.UTF_8).lines().map(line -> line.substring("jvm 1    | ".length()))
                .toList();
    }

    @Test
    public void everyLineLoggedIsPassedOnMaskedAndInPieces() {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        byte[] bytes = ("x " + SECRET + " y\n" + "z".repeat(OutputPump.MAX_LINE + 3))
                .getBytes(StandardCharsets.US_ASCII);
        try (WrapperLog log = WrapperLog.console(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), LogLevel.INFO)) {
            new OutputPump(new ByteArrayInputStream(bytes), StandardCharsets.US_ASCII, log.forSource("jvm 1"),
                    LogLevel.INFO, secrets(), seen::add).run();
        }

        assertEquals(seen.get(0), "x **** y");
        assertTrue(seen.size() >= 3, "the long line comes in pieces: " + seen.size());
        assertTrue(seen.get(seen.size() - 1).startsWith(OutputPump.CONTINUED + "zzz"), seen.get(seen.size() - 1));
    }

    @Test
    public void everyLineBreakEndsALine() {
        assertEquals(pump("a\nb\r\nc\rd".getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII),
                List.of("a", "b", "c", "d"));
        assertEquals(pump("\n\nx\n".getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII),
                List.of("", "", "x"));
    }

    @Test
    public void bytesAreDecodedWithTheGivenCharset() {
        String text = "Café ñandú\n";

        assertEquals(pump(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), List.of(text.trim()));
        assertEquals(pump(text.getBytes(Charset.forName("windows-1252")), Charset.forName("windows-1252")),
                List.of(text.trim()));
    }

    @Test
    public void aVeryLongLineIsLoggedInMarkedPieces() {
        String longLine = "x".repeat(OutputPump.MAX_LINE * 2 + 5);

        List<String> lines = pump((longLine + "\nafter\n").getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);

        assertEquals(lines.size(), 4, lines.toString().length() + " chars");
        assertEquals(lines.get(0), "x".repeat(OutputPump.MAX_LINE));
        assertEquals(lines.get(1), OutputPump.CONTINUED + "x".repeat(OutputPump.MAX_LINE));
        assertEquals(lines.get(2), OutputPump.CONTINUED + "xxxxx");
        assertEquals(lines.get(3), "after");
    }

    @Test
    public void aLineOfExactlyTheMaximumLeavesNoEmptyPiece() {
        String exact = "y".repeat(OutputPump.MAX_LINE);

        assertEquals(pump((exact + "\nz\n").getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII),
                List.of(exact, "z"));
    }

    @Test
    public void secretsEchoedByTheApplicationAreMasked() {
        List<String> lines = pumpWithSecret(("Unrecognized option: -X" + SECRET + "\n").getBytes(StandardCharsets.US_ASCII));

        assertEquals(lines, List.of("Unrecognized option: -X****"));
    }

    @Test
    public void aSecretAcrossThePlaceALongLineWouldBeCutIsMaskedWhole() {
        for (int offset = 1; offset < SECRET.length(); offset++) {
            String line = "x".repeat(OutputPump.MAX_LINE - offset) + SECRET + "y".repeat(10);

            List<String> lines = pumpWithSecret((line + "\n").getBytes(StandardCharsets.US_ASCII));

            String joined = String.join("", lines);
            assertFalse(joined.contains(SECRET.substring(0, 3)) && joined.contains("Secret"), "offset " + offset);
            for (String piece : lines) {
                for (int length = 4; length <= SECRET.length(); length++) {
                    for (int start = 0; start + length <= SECRET.length(); start++) {
                        assertFalse(piece.contains(SECRET.substring(start, start + length)),
                                "offset " + offset + " leaks " + SECRET.substring(start, start + length));
                    }
                }
            }
            assertTrue(joined.contains("****"), "offset " + offset);
        }
    }

    @Test
    public void whatWasReadBeforeAnErrorIsKept() {
        InputStream failing = new InputStream() {
            private final byte[] data = "partial".getBytes(StandardCharsets.US_ASCII);
            private int at;

            @Override
            public int read() throws IOException {
                if (at < data.length) {
                    return data[at++];
                }
                throw new IOException("pipe broken");
            }
        };

        assertEquals(pump(failing, StandardCharsets.US_ASCII), List.of("partial"));
    }
}

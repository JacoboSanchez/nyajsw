package io.github.jacobosanchez.nyajsw.launch;

import static org.testng.Assert.assertEquals;

import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.testng.annotations.Test;

public class OutputPumpTest {

    private static List<String> pump(byte[] bytes, Charset charset) {
        return pump(new ByteArrayInputStream(bytes), charset);
    }

    private static List<String> pump(InputStream in, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WrapperLog log = WrapperLog.console(new PrintStream(out, true, StandardCharsets.UTF_8), LogLevel.INFO)) {
            new OutputPump(in, charset, log.forSource("jvm 1"), LogLevel.INFO).run();
        }
        return out.toString(StandardCharsets.UTF_8).lines().map(line -> line.substring("jvm 1    | ".length()))
                .toList();
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

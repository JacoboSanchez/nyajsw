package io.jsl.nyajsw.dist;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

/**
 * The application {@link PackagedDistributionTest} runs under the unpacked distribution: it prints
 * the file name of the {@code -javaagent} it was started with and exits with the code given as its
 * first argument.
 */
public final class HelloApplication {

    static final String GREETING = "hello from the application; agent: ";

    private HelloApplication() {
    }

    public static void main(String[] args) {
        String agent = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:"))
                .map(argument -> Path.of(argument.substring("-javaagent:".length())).getFileName().toString())
                .findFirst().orElse("none");
        System.out.println(GREETING + agent);
        System.exit(args.length > 0 ? Integer.parseInt(args[0]) : 0);
    }
}

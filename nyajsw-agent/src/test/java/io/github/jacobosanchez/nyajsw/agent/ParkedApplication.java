package io.github.jacobosanchez.nyajsw.agent;

/**
 * An application for {@link AgentJarTest}: it registers a shutdown hook that prints {@code hook
 * ran} (where a security manager allows it), prints {@code parked} and waits until its JVM ends.
 */
public final class ParkedApplication {

    private ParkedApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook ran")));
        } catch (SecurityException e) {
            System.out.println("no hook"); // under a security manager that does not allow one
        }
        System.out.println("parked");
        Object never = new Object();
        synchronized (never) {
            while (true) {
                never.wait();
            }
        }
    }
}

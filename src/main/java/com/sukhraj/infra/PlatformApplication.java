package com.sukhraj.infra;

import com.sukhraj.infra.api.ApiServer;
import com.sukhraj.infra.client.AgentClient;
import com.sukhraj.infra.config.PlatformConfig;
import com.sukhraj.infra.monitoring.MonitoringService;
import java.util.concurrent.CountDownLatch;

public final class PlatformApplication {
    private PlatformApplication() {}

    public static void main(String[] args) {
        try {
            PlatformConfig config = PlatformConfig.fromEnvironment();
            MonitoringService monitoring = new MonitoringService(
                    config.nodes(),
                    new AgentClient(config.requestTimeout()),
                    config.pollInterval(),
                    config.availabilityWindow());
            ApiServer api = new ApiServer(config.bindAddress(), config.port(), monitoring, config.operatorToken());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                api.close();
                monitoring.close();
            }, "shutdown"));

            monitoring.start();
            api.start();
            System.out.printf(
                    "Infrastructure controller listening on http://%s:%d with %d node(s); operator routes %s%n",
                    config.bindAddress(), api.port(), config.nodes().size(),
                    config.operatorToken().isBlank() ? "disabled" : "enabled");
            new CountDownLatch(1).await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            System.err.println("Controller startup failed: " + exception.getMessage());
            System.exit(1);
        }
    }
}

package com.dsaplayground;

import java.io.IOException;

import com.dsaplayground.execution.JavaExecutionService;
import com.dsaplayground.execution.Marker;
import com.dsaplayground.execution.TestCaseResult;
import com.dsaplayground.service.EnvFileLoader;
import com.dsaplayground.service.FsService;
import com.dsaplayground.service.StartupBanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.annotation.Order;

@SpringBootApplication
@RegisterReflectionForBinding({TestCaseResult.class, Marker.class, FsService.Entry.class})
public class DsaPlaygroundApplication {

    private static final Logger log = LoggerFactory.getLogger(DsaPlaygroundApplication.class);

    public static void main(String[] args) {
        long mainStart = System.currentTimeMillis();
        log.trace("main() entry; args={} ({})", args == null ? "null" : args.length, join(args));

        // Load <working dir>/.env (Judge0 config, ports, …) before the Spring
        // context starts so every bean can read it via the Env helper. Real
        // shell-exported variables always win over .env values.
        EnvFileLoader.loadFromWorkingDirectory();

        SpringApplication app = new SpringApplication(DsaPlaygroundApplication.class);
        // Add listener that draws the banner once the app is ready to serve requests
        app.addListeners(new StartupBannerReadyListener());

        log.trace("SpringApplication configured; running...");
        ApplicationContext ctx = app.run(args);
        long mainEnd = System.currentTimeMillis();

        log.info("=== DsaPlaygroundApplication.main exit in {} ms ===", (mainEnd - mainStart));
        log.debug("ApplicationContext id: {}", ctx.getId());
        log.debug("Active profiles: {}", ctx.getEnvironment().getActiveProfiles());
        org.springframework.core.env.ConfigurableEnvironment ce = (org.springframework.core.env.ConfigurableEnvironment) ctx.getEnvironment();
        log.debug("Property sources (count={}):", ce.getPropertySources().size());
        ce.getPropertySources().forEach(ps ->
                log.trace("  property-source: name={} depth={}", ps.getName(), depthOf(ps)));
    }

    private static String join(String[] args) {
        if (args == null || args.length == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(args[i]));
        }
        return sb.toString();
    }

    private static int depthOf(PropertySource<?> ps) {
        int d = 0;
        Object source = ps.getSource();
        while (source instanceof PropertySource) {
            d++;
            source = ((PropertySource<?>) source).getSource();
        }
        return d;
    }

    /**
     * Listens for ApplicationReadyEvent to draw the final banner with real status
     * (workspace, JDK, server URL, startup duration). We also draw the initial
     * banner on the first command-line runner tick.
     */
    public static class StartupBannerReadyListener implements org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> {
        @Override
        public void onApplicationEvent(org.springframework.boot.context.event.ApplicationReadyEvent event) {
            log.trace("StartupBannerReadyListener.onApplicationEvent(ApplicationReadyEvent)");
            try {
                ApplicationContext ctx = event.getApplicationContext();
                FsService fs = ctx.getBean(FsService.class);
                JavaExecutionService executionService
                        = ctx.getBean(JavaExecutionService.class);
                String port = ctx.getEnvironment().getProperty("server.port", "8080");
                StartupBanner banner = new StartupBanner();
                banner.draw(fs.root().toString(), executionService.modeLabel(),
                        "http://localhost:" + port + "/", "ready", 0);
                log.info("Startup banner refreshed on ApplicationReadyEvent");
            } catch (Exception e) {
                log.warn("Could not refresh startup banner on ready: {}", e.getMessage());
            }
        }
    }

    /**
     * Ordered after the run listener so it sees the fully-started context, but still
     * prints the workspace line for the log.
     */
    @Bean
    @Order(2)
    CommandLineRunner logWorkspace(FsService fs) {
        return args -> {
            log.info("AlgoLab workspace root: {}", fs.root());
            log.debug("Workspace root absolute: {}", fs.root().toAbsolutePath().normalize());
            log.debug("Workspace root name: {}", fs.rootName());
            try {
                java.nio.file.Files.list(fs.root()).forEach(p ->
                        log.trace("  workspace-entry: {}", p.getFileName()));
            } catch (IOException e) {
                log.warn("Could not list workspace root contents: {}", e.getMessage());
            }
        };
    }
}

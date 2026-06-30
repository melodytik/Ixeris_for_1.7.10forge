package me.decce.ixeris;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Simple configuration for Ixeris 1.7.10.
 * Loaded from config/ixeris.cfg
 */
public class IxerisConfig {

    private static final File CONFIG_FILE = new File("config", "ixeris.cfg");

    public boolean enabled = true;
    /**
     * Polling interval in <b>microseconds</b> (µs).
     * 1000 µs = 1 ms.  Lower = smoother mouse but higher CPU usage.
     * 0 = pure busy-wait (highest CPU but lowest possible latency).
     * Recommended range: 100-2000 µs.  Default: 500 µs (0.5 ms).
     */
    public int pollingIntervalUs = 500;

    /**
     * Spin-wait mode for sub-millisecond waits.
     *
     * "yield" (default) — calls Thread.yield() periodically during spin-wait,
     *   giving the render thread CPU time.  Smoother, slightly more CPU.
     *
     * "park"  — always uses LockSupport.parkNanos(), never spin-waits.
     *   Lower CPU but may have ~1-2 µs extra latency.  Good for laptops.
     *
     * "busy"  — pure busy-loop with no yield / no park.  Maximum precision
     *   but risks starving the render thread on machines with few cores.
     */
    public String spinMode = "yield";

    public static IxerisConfig load() {
        IxerisConfig config = new IxerisConfig();
        Properties props = new Properties();

        if (CONFIG_FILE.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(CONFIG_FILE);
                props.load(fis);
                config.enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));

                // Support both old and new config keys
                if (props.containsKey("pollingIntervalUs")) {
                    config.pollingIntervalUs = parseInt(props.getProperty("pollingIntervalUs"), 500);
                } else if (props.containsKey("pollingInterval")) {
                    // Migrate old ms-based config → µs
                    config.pollingIntervalUs = parseInt(props.getProperty("pollingInterval"), 2) * 1000;
                } else if (props.containsKey("greedyPolling")) {
                    // Migrate very old boolean config
                    config.pollingIntervalUs = Boolean.parseBoolean(props.getProperty("greedyPolling")) ? 2000 : 8000;
                }

                // Spin mode
                config.spinMode = props.getProperty("spinMode", "yield").trim().toLowerCase();
            } catch (IOException e) {
                System.err.println("[Ixeris] Failed to read config: " + e);
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ignored) {}
            }
        }

        // Save to create/update the file
        save(config);
        return config;
    }

    public static void save(IxerisConfig config) {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(config.enabled));
        props.setProperty("pollingIntervalUs", String.valueOf(config.pollingIntervalUs));
        props.setProperty("spinMode", config.spinMode);

        File dir = CONFIG_FILE.getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(CONFIG_FILE);
            props.store(fos, "Ixeris 1.7.10 Configuration\n"
                    + "# pollingIntervalUs: event polling sleep in microseconds (0=busy-wait, 100-2000 recommended)\n"
                    + "# spinMode: 'yield' (smooth, default) | 'park' (low CPU) | 'busy' (max precision)");
        } catch (IOException e) {
            System.err.println("[Ixeris] Failed to save config: " + e);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

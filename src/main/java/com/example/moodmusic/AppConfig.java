package com.example.moodmusic;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    private final Properties props = new Properties();

    public void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {}
    }

    public int getCameraIndex() {
        return Integer.parseInt(props.getProperty("camera.index", "0"));
    }

    public int getFrameRate() {
        return Integer.parseInt(props.getProperty("camera.fps", "5"));
    }

    public String getMusicPathForMood(Mood mood) {
        return props.getProperty("mood." + mood.name().toLowerCase(), "");
    }

    public double getSmileThreshold() {
        return Double.parseDouble(props.getProperty("detect.smileThreshold", "1.3"));
    }

    public int getMinNeighbors() {
        return Integer.parseInt(props.getProperty("detect.minNeighbors", "5"));
    }

    public boolean isPreviewEnabled() {
        return Boolean.parseBoolean(props.getProperty("ui.preview", "true"));
    }

    public boolean isFastDetectEnabled() {
        return Boolean.parseBoolean(props.getProperty("detect.fast", "true"));
    }

    public int getMinSwitchMs() {
        return Integer.parseInt(props.getProperty("detect.minSwitchMs", "600"));
    }

    public int getConsecutiveOverride() {
        return Integer.parseInt(props.getProperty("detect.consecutiveOverride", "2"));
    }
}

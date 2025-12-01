package com.example.moodmusic;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    private static final Logger log = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        AppConfig config = new AppConfig();
        config.load();

        try (MusicPlayer musicPlayer = new MusicPlayer(config)) {
            WebcamService webcam = new WebcamService(config);
            MoodDetector detector = new MoodDetector(config);
            if (!webcam.open()) {
                log.severe("Failed to open webcam. Exiting.");
                // Provide audible feedback even if webcam isn't available
                log.info("Playing neutral fallback tone for 8 seconds so you can verify audio output...");
                musicPlayer.playForMood(Mood.NEUTRAL);
                try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
                return;
            }

            CameraPreviewWindow preview = null;
            if (config.isPreviewEnabled()) {
                try {
                    preview = new CameraPreviewWindow();
                } catch (Throwable t) {
                    log.log(Level.WARNING, "Unable to open preview window. Continuing without UI preview.", t);
                }
            }

            final WebcamService finalWebcam = webcam;
            final CameraPreviewWindow finalPreview = preview;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown requested. Releasing resources...");
                finalWebcam.close();
                try {
                    if (finalPreview != null) finalPreview.close();
                } catch (Exception ignored) {}
            }));

            Mood lastMood = null;
            long frameIntervalMs = Math.max(30, 1000L / Math.max(1, config.getFrameRate())) ;
            log.info("Starting mood detection loop.");
            while (webcam.isOpen()) {
                WebcamService.Frame frame = webcam.readFrame();
                if (frame == null) {
                    log.warning("No frame received from camera. Breaking loop.");
                    break;
                }
                Mood mood = detector.detect(frame);
                if (mood != null && mood != lastMood) {
                    log.info("Detected mood: " + mood + " (was: " + lastMood + ")");
                    musicPlayer.playForMood(mood);
                    lastMood = mood;
                }

                if (preview != null) {
                    try {
                        preview.update(frame.bgr, lastMood);
                    } catch (Exception e) {
                        log.log(Level.FINE, "Preview update failed", e);
                    }
                }

                try { Thread.sleep(frameIntervalMs); } catch (InterruptedException ignored) {}
            }

            finalWebcam.close();
            if (finalPreview != null) {
                try { finalPreview.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unexpected error in application:", e);
        }
    }
}

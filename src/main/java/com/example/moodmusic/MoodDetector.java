package com.example.moodmusic;

import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.util.logging.Logger;

public class MoodDetector {
    private static final Logger log = Logger.getLogger(MoodDetector.class.getName());

    private final AppConfig config;
    private CascadeClassifier faceCascade;
    private CascadeClassifier smileCascade;

    // For heuristic speed/robustness
    private Mat prevGraySmall; // for motion estimation
    private Mood lastMood;
    private long lastMoodChangeMs;
    private Mood lastRawDetection; // immediate (unsmoothed) detection result of previous frame
    private int consecutiveDifferentCount; // how many consecutive frames suggest a different mood

    public MoodDetector(AppConfig config) {
        this.config = config;
        // Try to load cascades from resources if present
        try {
            String facePath = resourcePath("haarcascade_frontalface_default.xml");
            String smilePath = resourcePath("haarcascade_smile.xml");
            if (facePath != null) {
                faceCascade = new CascadeClassifier(facePath);
            }
            if (smilePath != null) {
                smileCascade = new CascadeClassifier(smilePath);
            }
        } catch (Exception ignored) {}
    }

    private String resourcePath(String name) {
        try {
            var url = getClass().getClassLoader().getResource("cascades/" + name);
            return url != null ? url.getPath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Mood detect(WebcamService.Frame frame) {
        if (frame == null || frame.gray == null || frame.gray.empty()) return null;

        // Work on a smaller grayscale image for speed and stability
        Mat graySmall = fastDownscale(frame.gray, 320);

        Mood detected = null;
        if (faceCascade != null && !faceCascade.empty() && smileCascade != null && !smileCascade.empty()) {
            detected = detectWithCascadesFast(graySmall);
        }
        if (detected == null) {
            detected = detectWithEnhancedHeuristic(graySmall);
        }

        // Temporal smoothing with fast override: avoid flicker but react quickly to genuine changes
        long now = System.currentTimeMillis();
        int minSwitch = Math.max(0, config.getMinSwitchMs());
        int consecOverride = Math.max(1, config.getConsecutiveOverride());

        // Track raw detection continuity
        if (detected != null && detected != lastMood) {
            if (detected == lastRawDetection) {
                consecutiveDifferentCount++;
            } else {
                consecutiveDifferentCount = 1;
            }
        } else {
            consecutiveDifferentCount = 0;
        }
        lastRawDetection = detected;

        if (lastMood == null) {
            lastMood = detected;
            lastMoodChangeMs = now;
        } else if (detected != lastMood) {
            boolean timeOk = (now - lastMoodChangeMs) >= minSwitch;
            boolean consecutiveOk = consecutiveDifferentCount >= consecOverride;
            if (timeOk || consecutiveOk) {
                lastMood = detected;
                lastMoodChangeMs = now;
            } // else keep lastMood until minSwitch elapses
        }
        // Keep previous small gray for motion calculation next frame
        if (prevGraySmall != null) prevGraySmall.close();
        prevGraySmall = graySmall; // retain for next frame

        return lastMood;
    }

    private Mat fastDownscale(Mat gray, int targetWidth) {
        int w = gray.cols();
        int h = gray.rows();
        if (w <= targetWidth) return gray.clone();
        double scale = targetWidth / (double) w;
        Size sz = new Size((int) (w * scale), (int) (h * scale));
        Mat out = new Mat();
        resize(gray, out, sz, 0, 0, INTER_AREA);
        return out;
    }

    private Mood detectWithCascadesFast(Mat graySmall) {
        boolean fast = config.isFastDetectEnabled();
        double scaleFactor = fast ? 1.05 : 1.1;
        int neighbors = fast ? 3 : Math.max(3, config.getMinNeighbors());

        RectVector faces = new RectVector();
        faceCascade.detectMultiScale(graySmall, faces, scaleFactor, neighbors, 0, new Size(50, 50), new Size());
        boolean anySmile = false;
        for (long i = 0; i < faces.size(); i++) {
            Rect roi = faces.get(i);
            Mat faceROI = new Mat(graySmall, roi);
            // Improve contrast for smile detection
            Mat faceEq = new Mat();
            equalizeHist(faceROI, faceEq);
            RectVector smiles = new RectVector();
            // Lower thresholds in fast mode for quicker smile detection
            double smileScale = fast ? Math.max(1.1, config.getSmileThreshold() - 0.2) : config.getSmileThreshold();
            int smileNeighbors = fast ? 6 : Math.max(8, config.getMinNeighbors() + 4);
            smileCascade.detectMultiScale(faceEq, smiles, smileScale, smileNeighbors, 0, new Size(20, 20), new Size());
            if (smiles.size() > 0) {
                anySmile = true;
            }
            smiles.close();
            faceEq.close();
            faceROI.close();
            if (anySmile) break;
        }
        faces.close();
        return anySmile ? Mood.HAPPY : null; // return null to allow heuristic decide if not sure
    }

    private Mood detectWithEnhancedHeuristic(Mat graySmall) {
        // Brightness and contrast
        Mat mean = new Mat(1,1,CV_64FC1);
        Mat stddev = new Mat(1,1,CV_64FC1);
        meanStdDev(graySmall, mean, stddev);
        double m = mean.createIndexer().getDouble(0);
        double s = stddev.createIndexer().getDouble(0);
        mean.close(); stddev.close();

        // Edge/texture via Laplacian variance
        Mat lap = new Mat();
        Laplacian(graySmall, lap, CV_64F);
        meanStdDev(lap, mean = new Mat(1,1,CV_64FC1), stddev = new Mat(1,1,CV_64FC1));
        double edgeVar = stddev.createIndexer().getDouble(0);
        lap.close(); mean.close(); stddev.close();

        // Motion via frame differencing
        double motion = 0.0;
        if (prevGraySmall != null && !prevGraySmall.empty()) {
            Mat diff = new Mat();
            absdiff(graySmall, prevGraySmall, diff);
            meanStdDev(diff, mean = new Mat(1,1,CV_64FC1), stddev = new Mat(1,1,CV_64FC1));
            motion = mean.createIndexer().getDouble(0);
            diff.close(); mean.close(); stddev.close();
        }

        // Normalize thresholds based on 0..255 grayscale
        boolean bright = m > 120;
        boolean dark = m < 70;
        boolean highContrast = s > 50;
        boolean strongEdges = edgeVar > 12; // tuned empirically
        boolean moving = motion > 6;        // tuned empirically

        if (bright && (moving || strongEdges)) return Mood.HAPPY;
        if (dark && !moving && !highContrast) return Mood.CALM;
        if (dark && (highContrast || strongEdges)) return Mood.SAD;
        if (!dark && strongEdges && !moving) return Mood.SAD; // tense still image
        return Mood.NEUTRAL;
    }
}

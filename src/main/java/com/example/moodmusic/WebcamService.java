package com.example.moodmusic;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;

public class WebcamService {
    public static class Frame {
        public final Mat bgr;
        public final Mat gray;
        public Frame(Mat bgr, Mat gray) { this.bgr = bgr; this.gray = gray; }
    }

    private final AppConfig config;
    private VideoCapture capture;
    private boolean open;

    public WebcamService(AppConfig config) {
        this.config = config;
    }

    public boolean open() {
        if (open) return true;
        capture = new VideoCapture(config.getCameraIndex());
        open = capture.isOpened();
        return open;
    }

    public boolean isOpen() { return open; }

    public Frame readFrame() {
        if (!open) return null;
        Mat bgr = new Mat();
        if (!capture.read(bgr) || bgr.empty()) return null;
        Mat gray = new Mat();
        cvtColor(bgr, gray, COLOR_BGR2GRAY);
        return new Frame(bgr, gray);
    }

    public void close() {
        if (capture != null) {
            capture.release();
            capture.close();
        }
        open = false;
    }
}

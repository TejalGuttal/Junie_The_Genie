package com.example.moodmusic;

import org.bytedeco.opencv.opencv_core.Mat;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Simple Swing window to preview the webcam frames and current detected mood.
 */
public class CameraPreviewWindow {
    private final JFrame frame;
    private final JLabel imageLabel;

    public CameraPreviewWindow() {
        frame = new JFrame("Mood Camera Preview");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setVerticalAlignment(SwingConstants.CENTER);
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(imageLabel, BorderLayout.CENTER);
        frame.setSize(800, 600);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    public void update(Mat bgr, Mood mood) {
        if (bgr == null || bgr.empty()) return;
        BufferedImage img = matToBufferedImageBGR(bgr);
        if (img == null) return;
        final Image toShow;
        // Simple scaling to fit window while keeping aspect ratio
        int labelW = Math.max(1, imageLabel.getWidth());
        int labelH = Math.max(1, imageLabel.getHeight());
        if (labelW > 1 && labelH > 1) {
            double scale = Math.min(labelW / (double) img.getWidth(), labelH / (double) img.getHeight());
            if (scale > 0 && scale != 1.0) {
                toShow = img.getScaledInstance((int) (img.getWidth() * scale), (int) (img.getHeight() * scale), Image.SCALE_SMOOTH);
            } else {
                toShow = img;
            }
        } else {
            toShow = img;
        }
        SwingUtilities.invokeLater(() -> {
            if (mood != null) {
                frame.setTitle("Mood Camera Preview - Detected: " + mood.name());
            }
            imageLabel.setIcon(new ImageIcon(toShow));
        });
    }

    public void close() {
        frame.dispose();
    }

    // Convert 8UC3 (BGR) Mat to BufferedImage TYPE_3BYTE_BGR
    private static BufferedImage matToBufferedImageBGR(Mat mat) {
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();
        if (channels != 3) return null;
        byte[] data = new byte[width * height * channels];
        mat.data().get(data);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        image.getRaster().setDataElements(0, 0, width, height, data);
        return image;
    }
}

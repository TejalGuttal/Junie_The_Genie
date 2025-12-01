package com.example.moodmusic;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MusicPlayer implements AutoCloseable {
    private static final Logger log = Logger.getLogger(MusicPlayer.class.getName());

    private final AppConfig config;
    private final AtomicReference<Mood> currentMood = new AtomicReference<>();

    private Clip clip; // for WAV files
    private SourceDataLine toneLine; // fallback tone
    private Thread toneThread;
    private volatile boolean toneRunning;

    public MusicPlayer(AppConfig config) {
        this.config = Objects.requireNonNull(config);
        // Validate that configured songs are distinct across moods; warn if duplicates
        try {
            validateDistinctSongs();
        } catch (Exception ignored) {}
    }

    private void validateDistinctSongs() {
        try {
            String happy = nullSafe(config.getMusicPathForMood(Mood.HAPPY));
            String neutral = nullSafe(config.getMusicPathForMood(Mood.NEUTRAL));
            String calm = nullSafe(config.getMusicPathForMood(Mood.CALM));
            String sad = nullSafe(config.getMusicPathForMood(Mood.SAD));
            // Normalize case and trim
            happy = norm(happy); neutral = norm(neutral); calm = norm(calm); sad = norm(sad);
            if (!happy.isEmpty() && happy.equals(neutral)) log.warning("mood.happy and mood.neutral point to the same file. Configure different songs per mood for best experience.");
            if (!happy.isEmpty() && happy.equals(calm)) log.warning("mood.happy and mood.calm point to the same file. Configure different songs per mood for best experience.");
            if (!happy.isEmpty() && happy.equals(sad)) log.warning("mood.happy and mood.sad point to the same file. Configure different songs per mood for best experience.");
            if (!neutral.isEmpty() && neutral.equals(calm)) log.warning("mood.neutral and mood.calm point to the same file. Configure different songs per mood for best experience.");
            if (!neutral.isEmpty() && neutral.equals(sad)) log.warning("mood.neutral and mood.sad point to the same file. Configure different songs per mood for best experience.");
            if (!calm.isEmpty() && calm.equals(sad)) log.warning("mood.calm and mood.sad point to the same file. Configure different songs per mood for best experience.");
        } catch (Throwable t) {
            // Non-fatal
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static String norm(String s) { return s.trim().replace('\\','/').toLowerCase(); }

    public synchronized void playForMood(Mood mood) {
        if (mood == null) return;
        if (mood.equals(currentMood.get())) return;

        stopAll();
        currentMood.set(mood);

        String path = config.getMusicPathForMood(mood);
        if (path == null || path.isBlank()) {
            log.info("No music file configured for mood " + mood + ". Playing fallback tone.");
            playToneForMood(mood);
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            log.warning("Configured music file does not exist: " + f.getAbsolutePath() + ". Playing fallback tone.");
            playToneForMood(mood);
            return;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            AudioFormat baseFormat = in.getFormat();
            AudioFormat decoded = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(), 16, baseFormat.getChannels(),
                    baseFormat.getChannels() * 2, baseFormat.getSampleRate(), false);
            try (AudioInputStream din = AudioSystem.getAudioInputStream(decoded, in)) {
                clip = AudioSystem.getClip();
                clip.open(din);
                clip.loop(Clip.LOOP_CONTINUOUSLY);
                clip.start();
                log.info("Playing track for mood: " + mood + " -> " + f.getName());
            }
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            log.log(Level.WARNING, "Failed to play audio file. Falling back to tone.", e);
            playToneForMood(mood);
        }
    }

    private void playToneForMood(Mood mood) {
        float sampleRate = 44100f;
        // Define per-mood musical parameters
        Pattern p;
        switch (mood) {
            case HAPPY -> p = Pattern.happy();
            case CALM -> p = Pattern.calm();
            case SAD -> p = Pattern.sad();
            default -> p = Pattern.neutral();
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                log.warning("Audio output line not supported for fallback tone (format=" + format + ")");
                return;
            }
            toneLine = (SourceDataLine) AudioSystem.getLine(info);
            toneLine.open(format, 8192);
            toneLine.start();
            toneRunning = true;
            final Pattern fp = p;
            toneThread = new Thread(() -> generatePatternBeat(toneLine, fp));
            toneThread.setDaemon(true);
            toneThread.start();
            log.info("Playing fallback beat for mood: " + mood + " (" + p.bpm + " BPM, style=" + p.name + ")");
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            log.log(Level.WARNING, "Unable to start fallback tone output", e);
        }
    }

    // Distinct per-mood rhythm synthesizer: kick/snare/hat plus a gentle pad/base tone.
    private void generatePatternBeat(SourceDataLine line, Pattern p) {
        byte[] buf = new byte[2048];
        final double sr = line.getFormat().getSampleRate();

        // Base pad tone
        double padPhase = 0.0;
        final double padTwoPiF = 2 * Math.PI * p.padFreqHz;

        // Sequencer grid
        final int samplesPerBeat = (int) Math.round(sr * 60.0 / Math.max(1, p.bpm));
        final int stepsPerBeat = p.stepsPerBeat; // e.g., 4 = 16th notes
        final int samplesPerStep = Math.max(1, samplesPerBeat / stepsPerBeat);
        int sampleInStep = 0;
        int stepIndex = 0; // 0..(stepsPerBar-1)

        // Percussion envelopes
        double kickEnv = 0, kickFreq = p.kickBaseHz;
        double snareEnv = 0;
        double hatEnv = 0;
        double kickPitchEnv = 0;

        while (toneRunning) {
            for (int i = 0; i < buf.length; i += 2) {
                // Trigger events at step boundaries
                if (sampleInStep == 0) {
                    if (p.kickSteps[stepIndex]) { kickEnv = 1.0; kickPitchEnv = 1.0; }
                    if (p.snareSteps[stepIndex]) { snareEnv = 1.0; }
                    if (p.hatSteps[stepIndex]) { hatEnv = 1.0; }
                }

                // KICK: decaying sine with slight pitch drop
                double kick;
                if (kickEnv > 0) {
                    double kFreq = p.kickBaseHz + 40.0 * kickPitchEnv; // start a bit higher
                    double kPhaseInc = 2 * Math.PI * kFreq / sr;
                    // accumulate phase cheaply using padPhase as a base reference isn't ideal; compute directly via sin with per-sample increment
                    // Use a tiny local oscillator by integrating in a double
                    // To avoid extra state, approximate with sin of padPhase scaled is not correct; keep a separate state
                }

                // Maintain separate oscillator phases
                p.kickPhase += 2 * Math.PI * (p.kickBaseHz + 40.0 * kickPitchEnv) / sr;
                if (p.kickPhase > 2 * Math.PI) p.kickPhase -= 2 * Math.PI;

                double kickSample = Math.sin(p.kickPhase) * 0.9 * kickEnv;
                kickEnv *= p.kickDecay;
                kickPitchEnv *= p.kickPitchDecay;

                // SNARE: noise burst with fast decay
                double snareSample = (rand() * 2 - 1) * 0.5 * snareEnv;
                snareEnv *= p.snareDecay;

                // HAT: bright noise with very fast decay, a bit of HP feel by subtracting a smoothed component
                p.hatLp = 0.8 * p.hatLp + 0.2 * (rand() * 2 - 1);
                double hatNoise = (rand() * 2 - 1) - p.hatLp;
                double hatSample = hatNoise * 0.3 * hatEnv;
                hatEnv *= p.hatDecay;

                // PAD/base tone with gentle per-beat ducking
                double beatPos = (sampleInStep + (stepIndex % stepsPerBeat) * samplesPerStep) / (double) samplesPerBeat;
                double duck = 0.85 + 0.15 * Math.exp(-6.0 * (beatPos % 1.0));
                double pad = Math.sin(padPhase) * p.padVolume * duck;
                padPhase += padTwoPiF / sr;
                if (padPhase > 2 * Math.PI) padPhase -= 2 * Math.PI;

                double mixed = kickSample + snareSample + hatSample + pad;
                // Soft limiter
                mixed = Math.tanh(mixed * 1.8);

                short val = (short) Math.max(Math.min(mixed * Short.MAX_VALUE, Short.MAX_VALUE), Short.MIN_VALUE);
                buf[i] = (byte) (val & 0xFF);
                buf[i + 1] = (byte) ((val >> 8) & 0xFF);

                sampleInStep++;
                if (sampleInStep >= samplesPerStep) {
                    sampleInStep = 0;
                    stepIndex = (stepIndex + 1) % p.stepsPerBar;
                }
            }
            line.write(buf, 0, buf.length);
        }
        line.drain();
        line.stop();
        line.close();
    }

    // Simple PRNG for noise (Xorshift32)
    private int rngState = 0x12345678;
    private double rand() {
        int x = rngState;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        rngState = x;
        // Scale to [-1,1]
        return (x & 0x7FFFFFFF) / (double) 0x3FFFFFFF - 1.0;
    }

    // Pattern definition for per-mood beats
    private static class Pattern {
        final String name;
        final int bpm;
        final int stepsPerBeat; // 4 => 16th notes
        final int beatsPerBar;
        final int stepsPerBar;
        final boolean[] kickSteps;
        final boolean[] snareSteps;
        final boolean[] hatSteps;
        final double padFreqHz;
        final double padVolume;

        // Synth params (mutable state for oscillators/envelopes)
        double kickPhase = 0.0;
        double kickBaseHz = 60.0;
        double kickDecay = 0.995;      // amplitude decay per-sample
        double kickPitchDecay = 0.997; // pitch envelope decay
        double snareDecay = 0.97;
        double hatDecay = 0.90;
        double hatLp = 0.0;            // low-pass accumulator for hat HP effect

        private Pattern(String name, int bpm, int stepsPerBeat, int beatsPerBar,
                         boolean[] kick, boolean[] snare, boolean[] hat,
                         double padFreqHz, double padVol) {
            this.name = name;
            this.bpm = bpm;
            this.stepsPerBeat = stepsPerBeat;
            this.beatsPerBar = beatsPerBar;
            this.stepsPerBar = stepsPerBeat * beatsPerBar;
            this.kickSteps = kick;
            this.snareSteps = snare;
            this.hatSteps = hat;
            this.padFreqHz = padFreqHz;
            this.padVolume = padVol;
        }

        // Factory patterns
        static Pattern happy() {
            int bpm = 140;
            int spb = 4; // 16ths
            int bpb = 4;
            int n = spb * bpb;
            boolean[] kick = new boolean[n];
            boolean[] snare = new boolean[n];
            boolean[] hat = new boolean[n];
            for (int i = 0; i < n; i++) {
                int stepInBeat = i % spb;
                // Kick on 1 and the "and" of 2 (syncopation)
                if ((i % (spb * bpb)) == 0 || (i % (spb * bpb)) == spb * 1 + 2) kick[i] = true;
                // Snare on 2 and 4
                if ((i / spb) % bpb == 1 || (i / spb) % bpb == 3) snare[i] = (stepInBeat == 0);
                // Hi-hat on all 8ths (every 2 steps), open feel
                hat[i] = (stepInBeat % 2 == 0);
            }
            Pattern p = new Pattern("happy-4onfloor", bpm, spb, bpb, kick, snare, hat, 660.0, 0.18);
            p.kickBaseHz = 80.0; p.kickDecay = 0.996; p.kickPitchDecay = 0.995; p.snareDecay = 0.965; p.hatDecay = 0.90;
            return p;
        }

        static Pattern neutral() {
            int bpm = 100;
            int spb = 4; int bpb = 4; int n = spb * bpb;
            boolean[] kick = new boolean[n];
            boolean[] snare = new boolean[n];
            boolean[] hat = new boolean[n];
            for (int i = 0; i < n; i++) {
                int beat = (i / spb) % bpb;
                // Straight kick on every beat
                if (i % spb == 0) kick[i] = true;
                // Soft snare on 2 & 4
                if ((beat == 1 || beat == 3) && i % spb == 0) snare[i] = true;
                // Closed hat on quarters (simpler than happy)
                hat[i] = (i % spb == 0);
            }
            Pattern p = new Pattern("neutral-straight", bpm, spb, bpb, kick, snare, hat, 440.0, 0.12);
            p.kickBaseHz = 70.0; p.snareDecay = 0.955; p.hatDecay = 0.88;
            return p;
        }

        static Pattern calm() {
            // 6/8 lilting feel
            int bpm = 60; // beats here are dotted quarters; slower overall
            int spb = 3;  // treat 6/8 as 2 beats with 3 steps each
            int bpb = 2;
            int n = spb * bpb;
            boolean[] kick = new boolean[n];
            boolean[] snare = new boolean[n];
            boolean[] hat = new boolean[n];
            for (int i = 0; i < n; i++) {
                int step = i % spb;
                // Soft kick on the first step of each group
                kick[i] = (step == 0);
                // Very light snare on second group only, first step
                snare[i] = (i >= spb) && (step == 0);
                // Sparse hat on the middle step for lilt
                hat[i] = (step == 1);
            }
            Pattern p = new Pattern("calm-6_8", bpm, spb, bpb, kick, snare, hat, 432.0, 0.10);
            p.kickBaseHz = 55.0; p.kickDecay = 0.998; p.snareDecay = 0.985; p.hatDecay = 0.92;
            return p;
        }

        static Pattern sad() {
            int bpm = 75;
            int spb = 4; int bpb = 4; int n = spb * bpb;
            boolean[] kick = new boolean[n];
            boolean[] snare = new boolean[n];
            boolean[] hat = new boolean[n];
            for (int i = 0; i < n; i++) {
                int beat = (i / spb) % bpb;
                // Kick on 1, and a ghost on late 3 (syncopated)
                if ((i % spb == 0 && beat == 0) || (beat == 2 && i % spb == 3)) kick[i] = true;
                // Snare mainly on 3
                if (beat == 2 && i % spb == 0) snare[i] = true;
                // Tight hat on 8ths but reduced, for somber feel
                hat[i] = (i % 2 == 0) && (i % spb != 0);
            }
            Pattern p = new Pattern("sad-slowgroove", bpm, spb, bpb, kick, snare, hat, 247.0, 0.10);
            p.kickBaseHz = 50.0; p.snareDecay = 0.96; p.hatDecay = 0.85;
            return p;
        }
    }

    private synchronized void stopAll() {
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (Exception ignored) {}
            clip = null;
        }
        if (toneLine != null) {
            toneRunning = false;
            try {
                if (toneThread != null) toneThread.join(200);
            } catch (InterruptedException ignored) {}
            toneThread = null;
            toneLine = null;
        }
    }

    @Override
    public void close() {
        stopAll();
    }
}

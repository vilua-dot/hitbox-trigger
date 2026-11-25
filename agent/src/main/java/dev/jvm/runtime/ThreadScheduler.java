package dev.jvm.runtime;

import java.util.concurrent.ThreadLocalRandom;

public class ThreadScheduler {

    private static final double RM = 170.0;
    private static final double RS = 28.0;
    private static final double PM = 35.0;
    private static final double PS = 12.0;
    private static final double MM = 18.0;
    private static final double MS = 6.0;
    private static final double FPA = 0.4;
    private static final double FRR = 0.15;
    private static final double MF = 60.0;
    private static final int BT = 3;
    private static final double BPL = 180.0;
    private static final double BPH = 450.0;
    private static final long WD = 25000;
    private static final double WE = 40.0;
    private static final double HC = 0.04;
    private static final double HL = 80.0;
    private static final double HH = 280.0;
    private static final double DM = 48.0;
    private static final double DS = 14.0;
    private static final double DL = 25.0;
    private static final double DH = 95.0;

    private long epoch;
    private double drift = 0.0;
    private long lastExec = 0;
    private int consecutive = 0;
    private long readyMark = 0;
    private final long[] recent = new long[20];
    private int idx = 0;

    public ThreadScheduler() {
        epoch = System.currentTimeMillis();
    }

    public void reset() {
        epoch = System.currentTimeMillis();
        drift = 0.0;
        consecutive = 0;
        lastExec = 0;
        readyMark = 0;
    }

    public void onReady() {
        if (readyMark == 0) readyMark = System.currentTimeMillis();
    }

    public void onCancel() {
        readyMark = 0;
    }

    public boolean isElapsed(long stateTime) {
        if (readyMark == 0) return false;
        long now = System.currentTimeMillis();
        long stimulus = Math.max(readyMark, stateTime);
        return (now - stimulus) >= computeDelay();
    }

    public long computeDelay() {
        double d = 0.0;
        d += gp(RM, RS);
        d += gp(PM, PS);
        d += gp(MM, MS);
        d += Math.min(drift, MF);

        long age = System.currentTimeMillis() - epoch;
        if (age < WD) {
            double f = 1.0 - ((double) age / WD);
            d += WE * f * (0.7 + ThreadLocalRandom.current().nextDouble() * 0.6);
        }

        if (consecutive >= BT) {
            d += BPL + ThreadLocalRandom.current().nextDouble() * (BPH - BPL);
            consecutive = 0;
        }

        if (ThreadLocalRandom.current().nextDouble() < HC) {
            d += HL + ThreadLocalRandom.current().nextDouble() * (HH - HL);
        }

        return Math.max((long) d, 100);
    }

    public void onExecuted() {
        long now = System.currentTimeMillis();
        long gap = lastExec > 0 ? now - lastExec : 999999;
        drift += FPA;
        if (gap < 400) consecutive++;
        else consecutive = Math.max(0, consecutive - 1);
        recent[idx % recent.length] = gap;
        idx++;
        lastExec = now;
        readyMark = 0;
    }

    public void onIdle(long interval) {
        if (drift > 0) drift = Math.max(0, drift - FRR * interval);
    }

    public int getHoldDuration() {
        double h = gp(DM, DS);
        return (int) Math.max(DL, Math.min(DH, h));
    }

    public boolean shouldDefer() {
        double base = 0.025;
        double bonus = (drift / MF) * 0.02;
        return ThreadLocalRandom.current().nextDouble() < (base + bonus);
    }

    private double gp(double mean, double sd) {
        double v = mean + ThreadLocalRandom.current().nextGaussian() * sd;
        return Math.max(v, mean * 0.4);
    }
}

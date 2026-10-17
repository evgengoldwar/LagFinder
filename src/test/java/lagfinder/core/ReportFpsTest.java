package lagfinder.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReportFpsTest {

    private static Report frames(float frameMs, float waitMs, float cap) {
        Report report = new Report();
        report.mode = ScanMode.FPS;
        report.periods = Math.round(10_000 / frameMs);
        report.durationSec = report.periods * frameMs / 1000f;
        report.bucketSec = report.durationSec;
        report.rate = new float[] { 1000f / frameMs };
        report.avgMs = new float[] { frameMs };
        report.maxMs = new float[] { frameMs };
        report.counts = new int[] { report.periods };
        report.waitMs = new float[] { waitMs };
        report.frameCap = cap;
        return report;
    }

    @Test
    public void averageRateIsFramesOverMeasuredTime() {
        Report report = frames(10, 0, 0);
        assertEquals(100, report.averageRate(), 0.01);
        report.overheadSec = report.durationSec / 2;
        assertEquals(200, report.averageRate(), 0.01);
    }

    @Test
    public void uncappedCostIsTheRateGainedWithoutIt() {
        Report report = frames(10, 0, 0);
        assertTrue(report.costsInFps());
        assertEquals(1000 / 8.0 - 100, report.fpsCost(2), 0.01);
        assertEquals(1000 / 8.0 - 100, report.fpsCost(2, 0), 0.01);
    }

    @Test
    public void cappedFramesCountWithoutTheLimit() {
        Report report = frames(1000 / 60f, 8, 60);
        assertTrue(report.costsInFps());
        assertTrue(report.heldToLimit());
        double work = 1000 / 60.0 - 8;
        assertEquals(1000 / work, report.unlimitedRate(), 0.5);
        assertEquals(1000 / (work - 2) - 1000 / work, report.fpsCost(2), 0.5);
        assertEquals(1000 / work - 1000 / (work + 3), report.fpsDrop(1, 4), 0.5);
    }

    @Test
    public void framesSlowerThanTheCapGainUpToIt() {
        Report report = frames(25, 0, 60);
        assertTrue(report.costsInFps());
        assertEquals(1000 / 23.0 - 40, report.fpsCost(2), 0.01);
        assertEquals(60 - 40, report.fpsCost(20), 0.01);
    }

    @Test
    public void capAboveTheFramesSeenDoesNotHideTheCost() {
        Report report = frames(10, 0, 60);
        assertTrue(report.costsInFps());
        assertEquals(1000 / 8.0 - 100, report.fpsCost(2), 0.01);
        assertEquals(0, report.fpsDrop(1, 1), 0.001);
        assertEquals(100 - 1000 / 12.0, report.fpsDrop(1, 3), 0.01);
    }

    @Test
    public void framesJustAboveTheCapStillCountAsHeld() {
        Report report = frames(1000 / 61f, 8, 60);
        assertTrue(report.heldToLimit());
        double work = 1000 / 61.0 - 8;
        assertEquals(1000 / (work - 2) - 1000 / work, report.fpsCost(2), 0.5);
    }
}

package org.thoughtcrime.securesms.calls;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import org.junit.Test;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

public class CallCaptureFormatTest {
  @Test
  public void prefers720pWhenTheCameraCanKeepUp() {
    assertArrayEquals(new int[] {1280, 720, 30}, MediaStreamManager.chooseCaptureFormat(Arrays.asList(
        new CaptureFormat(1920, 1080, 15000, 30000),
        new CaptureFormat(1280, 720, 15000, 29970),
        new CaptureFormat(640, 480, 15000, 30000))));
  }

  @Test
  public void avoidsSlowHighResolutionCameraModes() {
    assertArrayEquals(new int[] {960, 540, 30}, MediaStreamManager.chooseCaptureFormat(Arrays.asList(
        new CaptureFormat(1280, 720, 10000, 15000),
        new CaptureFormat(960, 540, 15000, 30000),
        new CaptureFormat(640, 480, 15000, 30000))));
  }

  @Test
  public void usesTheFastestModeWhenThirtyFpsIsUnavailable() {
    assertArrayEquals(new int[] {640, 480, 24}, MediaStreamManager.chooseCaptureFormat(Arrays.asList(
        new CaptureFormat(1280, 720, 10000, 15000),
        new CaptureFormat(640, 480, 15000, 24000))));
  }
}

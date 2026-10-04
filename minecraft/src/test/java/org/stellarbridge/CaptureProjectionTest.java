package org.stellarbridge;

import java.nio.charset.StandardCharsets;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CaptureProjectionTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    public static void main(String[] args) {
        var frame = frame(0, 10, 0);
        var camera = CaptureProjection.camera(frame, new CaptureFrame.Position(100, 64, -100));
        assert camera != null : "Linked capture must produce its own frozen camera";
        // Native feet=(1000,2000,3000), camera offset=(100,200,300) UU, LINK=(1,2,3).
        near(camera.x(), 102); near(camera.y(), 69); near(camera.z(), -95);
        near(camera.yaw(), -90); near(camera.pitch(), -10);
        near(CaptureProjection.camera(frame(90, 0, 0), new CaptureFrame.Position(0, 0, 0)).yaw(), 0);
        near(CaptureProjection.camera(frame(180, 0, 0), new CaptureFrame.Position(0, 0, 0)).yaw(), 90);
        near(camera.verticalFov(), Math.toDegrees(2 * Math.atan(0.5625)));
        near(CaptureProjection.horizontalExtent(1920, 1080), 1);
        near(CaptureProjection.horizontalExtent(2560, 1080), 0.75);
        near(CaptureProjection.horizontalExtent(1440, 1080), 4.0 / 3.0);
        // A capture's horizontal edge projects to the same viewport coordinate after the aspect correction.
        near((1 / Math.tan(Math.toRadians(camera.verticalFov() / 2))) / (2560.0 / 1080), 0.75);
        assert CaptureProjection.matches(frame, SESSION, 5, 1791110030);
        assert !CaptureProjection.matches(frame, SESSION, 4, 1791110000);
        assert !CaptureProjection.matches(frame, SESSION, 6, 1791110000)
                : "A still must not reuse EVE depth after Minecraft terrain advances";
        assert !CaptureProjection.matches(frame, "ffffffffffffffffffffffffffffffff", 5, 1791110000);
        assert !CaptureProjection.matches(frame, SESSION, 5, 1791110031);
        try { CaptureProjection.camera(frame(0, 0, 1), new CaptureFrame.Position(0, 0, 0));
            throw new AssertionError("Roll must not be silently discarded");
        } catch (IllegalArgumentException expected) { }
        // Perspective near=0.05, far=100: 5 UU maps to near; 10000 UU maps to far.
        near(depth(5, 100), 0); near(depth(10000, 100), 1);
        assert depth(100, 100) < depth(500, 100) : "EVE depth must preserve foreground ordering";
        // Actual native sample: the calibrated UE(X,Y,Z) -> MC(X,Z,Y) mapping preserves EVE's screen position.
        double nativeYaw = Math.toRadians(145.451675), dx = 40694.9141 - 40974.875, dy = -16665.5 + 16904.2051;
        double nativeRight = -Math.sin(nativeYaw) * dx + Math.cos(nativeYaw) * dy;
        double nativeForward = Math.cos(nativeYaw) * dx + Math.sin(nativeYaw) * dy;
        double nativeNdc = nativeRight / (nativeForward * Math.tan(Math.toRadians(75.0 / 2)));
        float mcYaw = (float) Math.toRadians(145.451675 - 90);
        var view = new Quaternionf().rotationYXZ((float) Math.PI - mcYaw, 0, 0).conjugate();
        float vertical = (float) (2 * Math.atan(Math.tan(Math.toRadians(75.0 / 2)) / (320.0 / 180)));
        var point = new Matrix4f().perspective(vertical, 320f / 180, 0.05f, 100)
                .rotate(view).transformProject(new Vector3f((float) dx / 100, -0.406992f, (float) dy / 100));
        assert nativeNdc < 0 && point.x < 0;
        near(point.x, nativeNdc);
        System.out.println("Capture projection tests passed");
    }

    private static double depth(double unrealDepth, double scale) {
        double d = CaptureProjection.blocks(unrealDepth, scale);
        double clipZ = -(100.0 + 0.05) / (100.0 - 0.05) * -d - 2 * 100 * 0.05 / (100.0 - 0.05);
        return (clipZ / d + 1) / 2;
    }
    private static CaptureFrame.Metadata frame(double yaw, double pitch, double roll) {
        String text = "SBMC_FRAME 1 1791110000-1 1791110000\nSIZE 320 180\n"
                + "CAMERA 1100 2200 3300 " + pitch + " " + yaw + " " + roll + " 90\n"
                + "PAWN 1000 2000 3095 95\nCONTEXT 11 12\nLINK " + SESSION + " 5 100 1 2 3\n"
                + "COLOR eve-frame-1791110000-1-color.png 1000\nMASK eve-frame-1791110000-1-mask.png 1000\n"
                + "DEPTH eve-frame-1791110000-1-depth.hdr 1000\nEND 1791110000-1\n";
        return CaptureFrame.parse(text.getBytes(StandardCharsets.US_ASCII));
    }
    private static void near(double actual, double expected) {
        assert Math.abs(actual - expected) < 0.00001 : actual + " differs from " + expected;
    }
}

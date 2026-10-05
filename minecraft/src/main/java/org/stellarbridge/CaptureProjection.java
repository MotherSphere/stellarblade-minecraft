package org.stellarbridge;

/** Coordinate conversion for a captured frame, independent of later player movement. */
public final class CaptureProjection {
    public record CameraPose(double x, double y, double z, float yaw, float pitch, double verticalFov) { }
    static CameraPose camera(CaptureFrame.Metadata frame, CaptureFrame.Position origin) {
        var camera = frame.camera();
        var pawn = frame.pawn();
        var link = frame.link();
        require(!link.session().equals("none") && Math.abs(camera.roll()) <= 0.01, "A linked capture without camera roll is required");
        require(Math.abs(camera.pitch()) <= 90 && Float.isFinite((float) link.scale()) && (float) link.scale() > 0,
                "Capture angles or scale cannot be rendered");
        double x = origin.x() + link.feet().x() + blocks(camera.position().x() - pawn.position().x(), link.scale());
        double y = origin.y() + link.feet().y() + blocks(camera.position().z() - pawn.position().z() + pawn.halfHeight(), link.scale());
        double z = origin.z() + link.feet().z() + blocks(camera.position().y() - pawn.position().y(), link.scale());
        require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= 30000000 && Math.abs(y) <= 30000000 && Math.abs(z) <= 30000000,
                "Capture camera is outside supported world coordinates");
        double vertical = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(frame.fov() / 2)) / ((double) frame.width() / frame.height())));
        require(vertical > 0 && vertical < 180, "Invalid capture projection");
        return new CameraPose(x, y, z, (float) Math.IEEEremainder(camera.yaw() - 90, 360), (float) -camera.pitch(), vertical);
    }
    static double horizontalExtent(int width, int height) {
        return horizontalExtent(width, height, 16, 9);
    }
    static double horizontalExtent(int width, int height, int captureWidth, int captureHeight) {
        require(width > 0 && height > 0, "Invalid viewport");
        require(captureWidth > 0 && captureHeight > 0, "Invalid capture dimensions");
        return ((double) captureWidth / captureHeight) / ((double) width / height);
    }
    static double blocks(double units, double scale) {
        require(Double.isFinite(units) && Double.isFinite(scale) && scale > 0, "Invalid coordinate scale");
        return units / scale;
    }
    static boolean matches(CaptureFrame.Metadata frame, String session, int revision, long now) {
        return !frame.link().session().equals("none") && frame.link().session().equals(session)
                && frame.link().ack() > 0 && frame.link().ack() == revision && CaptureFrame.fresh(frame.time(), now);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

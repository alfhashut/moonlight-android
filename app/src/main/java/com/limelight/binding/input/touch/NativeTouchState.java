package com.limelight.binding.input.touch;

import com.limelight.nvstream.jni.MoonBridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Android-independent state/geometry for native touchscreen packets.
 * Wire constants and field conventions come from MoonBridge and Limelight.h.
 * Pointer IDs are opaque: indexes in a frame are never used as contact IDs.
 */
final class NativeTouchState {
    enum Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    interface Sender {
        int send(byte type, int id, float x, float y, float pressure,
                 float major, float minor, short rotation);
    }

    interface ErrorListener {
        void onError(byte type, int id, int result);
    }

    static final class Pointer {
        final int id;
        final float x, y, pressure, major, minor, orientation;
        final boolean hasOrientation;

        Pointer(int id, float x, float y, float pressure, float major, float minor,
                float orientation, boolean hasOrientation) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.major = major;
            this.minor = minor;
            this.orientation = orientation;
            this.hasOrientation = hasOrientation;
        }
    }

    static final class Geometry {
        final float width, height, offsetX, offsetY;

        Geometry(float width, float height, float offsetX, float offsetY) {
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        boolean isValid() {
            return isFinite(width) && isFinite(height) && width > 0 && height > 0 &&
                    isFinite(offsetX) && isFinite(offsetY);
        }
    }

    private final Sender sender;
    private final ErrorListener errors;
    private final Set<Integer> active = new LinkedHashSet<>();
    private boolean failed;

    NativeTouchState(Sender sender, ErrorListener errors) {
        this.sender = sender;
        this.errors = errors;
    }

    int activePointerCount() {
        return active.size();
    }

    void handle(Action action, int actionIndex, List<Pointer> pointers,
                boolean canceled, Geometry geometry) {
        // A failed stream never silently changes over to mouse input or retries.
        // A new streaming session constructs a fresh context.
        if (failed) {
            return;
        }
        if (action == Action.CANCEL) {
            cancelAll(true);
            return;
        }
        if (action == Action.DOWN && !active.isEmpty()) {
            // A fresh Android gesture must not inherit contacts from a lost UP.
            cancel();
            if (failed) {
                return;
            }
        }
        if (!geometry.isValid()) {
            cancel();
            return;
        }
        if (action == Action.MOVE) {
            for (Pointer pointer : pointers) {
                if (active.contains(pointer.id) &&
                        !sendPointer(MoonBridge.LI_TOUCH_EVENT_MOVE, pointer, geometry)) {
                    break;
                }
            }
            return;
        }
        Pointer pointer = pointers.get(actionIndex);
        if (action == Action.DOWN || action == Action.POINTER_DOWN) {
            if (!active.contains(pointer.id) &&
                    sendPointer(MoonBridge.LI_TOUCH_EVENT_DOWN, pointer, geometry)) {
                active.add(pointer.id);
            }
        }
        else if (active.contains(pointer.id)) {
            boolean sent = canceled ?
                    send(MoonBridge.LI_TOUCH_EVENT_CANCEL, pointer.id, 0, 0, 0, 0, 0,
                            MoonBridge.LI_ROT_UNKNOWN) :
                    sendPointer(MoonBridge.LI_TOUCH_EVENT_UP, pointer, geometry);
            if (sent) {
                active.remove(pointer.id);
            }
            if (action == Action.UP) {
                cancel(); // Normally empty; also release any inconsistent leftover IDs.
            }
        }
    }

    void cancel() {
        cancelAll(false);
    }

    private void cancelAll(boolean force) {
        if (!failed && (force || !active.isEmpty())) {
            send(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, 0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN);
        }
        active.clear();
    }

    private boolean sendPointer(byte type, Pointer pointer, Geometry geometry) {
        float x = unit((pointer.x - geometry.offsetX) / geometry.width);
        float y = unit((pointer.y - geometry.offsetY) / geometry.height);
        float major = positive(pointer.major);
        // Limelight.h requires a circular contact if only the major axis is known.
        float minor = pointer.minor > 0 ? positive(pointer.minor) : major;
        float orientation = pointer.hasOrientation && isFinite(pointer.orientation) ?
                pointer.orientation : (float) (Math.PI / 4);

        // Same axis projection convention as Game.getStreamViewNormalizedContactArea().
        // No phone or stream resolution is hardcoded here.
        float majorX = Math.min(Math.abs((float) (major * Math.cos(orientation))), geometry.width) / geometry.width;
        float majorY = Math.min(Math.abs((float) (major * Math.sin(orientation))), geometry.height) / geometry.height;
        float minorX = Math.min(Math.abs((float) (minor * Math.cos(orientation + Math.PI / 2))), geometry.width) / geometry.width;
        float minorY = Math.min(Math.abs((float) (minor * Math.sin(orientation + Math.PI / 2))), geometry.height) / geometry.height;
        short rotation = MoonBridge.LI_ROT_UNKNOWN;
        if (pointer.hasOrientation && isFinite(pointer.orientation)) {
            int degrees = (int) Math.toDegrees(pointer.orientation);
            rotation = (short) ((degrees % 360 + 360) % 360);
        }
        return send(type, pointer.id, x, y, unit(pointer.pressure),
                unit((float) Math.hypot(majorX, majorY)),
                unit((float) Math.hypot(minorX, minorY)), rotation);
    }

    private boolean send(byte type, int id, float x, float y, float pressure,
                         float major, float minor, short rotation) {
        int result = sender.send(type, id, x, y, pressure, major, minor, rotation);
        if (result == 0) {
            return true;
        }
        failed = true;
        errors.onError(type, id, result);
        if (!active.isEmpty() && type != MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL) {
            // One best-effort remote cancellation, never a replay of the failed input.
            int cancelResult = sender.send(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0, MoonBridge.LI_ROT_UNKNOWN);
            if (cancelResult != 0) {
                errors.onError(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, cancelResult);
            }
        }
        active.clear();
        return false;
    }

    private static boolean isFinite(float value) {
        // Keep the opt-in path compatible with the app's Android 5.0 minimum.
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float positive(float value) {
        return isFinite(value) ? Math.max(value, 0) : 0;
    }

    private static float unit(float value) {
        return isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}

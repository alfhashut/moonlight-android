package com.limelight.binding.input.touch;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;

import java.util.ArrayList;
import java.util.List;

/** Opt-in finger input only. This context has no mouse/pen/local injection API. */
public final class NativeTouchContext {
    public interface ErrorListener {
        void onError(int result);
    }

    private final View streamView;
    private final NativeTouchState state;
    private boolean reportedError;

    public NativeTouchContext(NvConnection connection, View streamView, ErrorListener errors) {
        this.streamView = streamView;
        state = new NativeTouchState(connection::sendTouchEvent, (type, id, result) -> {
            LimeLog.warning("Native touchscreen packet failed: type=" + type +
                    " pointerId=" + id + " result=" + result);
            if (!reportedError) {
                reportedError = true;
                errors.onError(result);
            }
        });
    }

    /**
     * Returns true for touchscreen events owned by this mode, including failed
     * sends. A failed native gesture must never also reach a mouse context.
     * Game's mouse/stylus/controller routing runs before this finger-only path.
     */
    public boolean handleTouchEvent(View sourceView, MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            return false;
        }
        NativeTouchState.Action action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                action = NativeTouchState.Action.DOWN;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                action = NativeTouchState.Action.POINTER_DOWN;
                break;
            case MotionEvent.ACTION_MOVE:
                action = NativeTouchState.Action.MOVE;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                action = NativeTouchState.Action.POINTER_UP;
                break;
            case MotionEvent.ACTION_UP:
                action = NativeTouchState.Action.UP;
                break;
            case MotionEvent.ACTION_CANCEL:
                state.handle(NativeTouchState.Action.CANCEL, 0, java.util.Collections.emptyList(),
                        false, new NativeTouchState.Geometry(0, 0, 0, 0));
                return true;
            default:
                return true;
        }

        InputDevice device = event.getDevice();
        boolean hasOrientation = device != null &&
                device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null;
        InputDevice.MotionRange pressureRange = device == null ? null :
                device.getMotionRange(MotionEvent.AXIS_PRESSURE, event.getSource());
        List<NativeTouchState.Pointer> pointers = new ArrayList<>(event.getPointerCount());
        int actionIndex = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) {
                continue;
            }
            if (i == event.getActionIndex()) {
                actionIndex = pointers.size();
            }
            float pressure = event.getPressure(i);
            if (pressureRange != null && pressureRange.getRange() > 0) {
                pressure = (pressure - pressureRange.getMin()) / pressureRange.getRange();
            }
            pointers.add(new NativeTouchState.Pointer(event.getPointerId(i),
                    event.getX(i), event.getY(i), pressure,
                    event.getTouchMajor(i), event.getTouchMinor(i),
                    event.getOrientation(i), hasOrientation));
        }
        if (action != NativeTouchState.Action.MOVE && actionIndex < 0) {
            return true; // The changed pointer is not a finger; never encode it as touch.
        }

        // Match Game's existing background/StreamView coordinate transform.
        // The backgroundTouchView is a sibling filling the same parent; StreamView
        // is the actual aspect-fitted video view, not necessarily the phone size.
        NativeTouchState.Geometry geometry = new NativeTouchState.Geometry(
                streamView.getWidth(), streamView.getHeight(),
                sourceView != streamView ? streamView.getX() : 0,
                sourceView != streamView ? streamView.getY() : 0);
        state.handle(action, actionIndex, pointers,
                (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0, geometry);
        return true;
    }

    public void cancel() {
        state.cancel();
    }
}

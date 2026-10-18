package com.limelight.binding.input.touch;

import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.limelight.binding.input.touch.NativeTouchState.Action.*;

/** Host-JVM tests using the real MoonBridge constants; no Android/JNI runtime. */
public final class NativeTouchStateTest {
    private static int tests;
    private static final NativeTouchState.Geometry VIDEO =
            new NativeTouchState.Geometry(1000, 500, 0, 0);

    private static final class Packet {
        final byte type;
        final int id;
        final float x, y, pressure, major, minor;
        final short rotation;

        Packet(byte type, int id, float x, float y, float pressure,
               float major, float minor, short rotation) {
            this.type = type;
            this.id = id;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.major = major;
            this.minor = minor;
            this.rotation = rotation;
        }
    }

    private static final class Fixture {
        final List<Packet> packets = new ArrayList<>();
        final List<Integer> errors = new ArrayList<>();
        int result;
        final NativeTouchState state = new NativeTouchState((type, id, x, y, pressure, major, minor, rotation) -> {
            packets.add(new Packet(type, id, x, y, pressure, major, minor, rotation));
            return result;
        }, (type, id, error) -> errors.add(error));

        void event(NativeTouchState.Action action, int index, NativeTouchState.Pointer... pointers) {
            state.handle(action, index, Arrays.asList(pointers), false, VIDEO);
        }

        void sequence(int... typesAndIds) {
            check(packets.size() * 2 == typesAndIds.length, "packet count: " + packets.size());
            for (int i = 0; i < packets.size(); i++) {
                check(packets.get(i).type == typesAndIds[2 * i], "type at " + i);
                check(packets.get(i).id == typesAndIds[2 * i + 1], "id at " + i);
            }
        }
    }

    private static NativeTouchState.Pointer finger(int id, float x, float y) {
        return new NativeTouchState.Pointer(id, x, y, 0.5f, 0, 0, 0, false);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(float actual, float expected) {
        check(Math.abs(actual - expected) < 0.0001f, actual + " != " + expected);
    }

    private static void test(String name, Runnable body) {
        body.run();
        tests++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) {
        test("one finger down/move/up", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(7, 100, 100));
            f.event(MOVE, 0, finger(7, 200, 150));
            f.event(UP, 0, finger(7, 200, 150));
            f.sequence(MoonBridge.LI_TOUCH_EVENT_DOWN, 7, MoonBridge.LI_TOUCH_EVENT_MOVE, 7,
                    MoonBridge.LI_TOUCH_EVENT_UP, 7);
            near(f.packets.get(1).x, 0.2f);
            near(f.packets.get(1).y, 0.3f);
            check(f.state.activePointerCount() == 0, "released");
        });

        test("two distinct IDs, both moved, second up then final up", () -> {
            Fixture f = new Fixture();
            NativeTouchState.Pointer p = finger(9, 100, 200), q = finger(23, 500, 400);
            f.event(DOWN, 0, p);
            f.event(POINTER_DOWN, 1, p, q);
            f.event(MOVE, 0, p, q);
            check(f.state.activePointerCount() == 2, "simultaneous contacts");
            f.event(POINTER_UP, 1, p, q);
            check(f.state.activePointerCount() == 1, "first still active");
            f.event(UP, 0, p);
            f.sequence(MoonBridge.LI_TOUCH_EVENT_DOWN, 9, MoonBridge.LI_TOUCH_EVENT_DOWN, 23,
                    MoonBridge.LI_TOUCH_EVENT_MOVE, 9, MoonBridge.LI_TOUCH_EVENT_MOVE, 23,
                    MoonBridge.LI_TOUCH_EVENT_UP, 23, MoonBridge.LI_TOUCH_EVENT_UP, 9);
        });

        test("pointer indexes reorder while IDs remain stable", () -> {
            Fixture f = new Fixture();
            NativeTouchState.Pointer p = finger(12, 100, 200), q = finger(3, 500, 400);
            f.event(DOWN, 0, p);
            f.event(POINTER_DOWN, 1, p, q);
            f.event(MOVE, 0, q, p);
            f.event(POINTER_UP, 1, q, p);
            f.event(MOVE, 0, q);
            f.event(UP, 0, q);
            f.sequence(MoonBridge.LI_TOUCH_EVENT_DOWN, 12, MoonBridge.LI_TOUCH_EVENT_DOWN, 3,
                    MoonBridge.LI_TOUCH_EVENT_MOVE, 3, MoonBridge.LI_TOUCH_EVENT_MOVE, 12,
                    MoonBridge.LI_TOUCH_EVENT_UP, 12, MoonBridge.LI_TOUCH_EVENT_MOVE, 3,
                    MoonBridge.LI_TOUCH_EVENT_UP, 3);
        });

        test("ACTION_CANCEL sends CANCEL_ALL with unknown optional fields", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(2, 1, 2));
            f.event(POINTER_DOWN, 1, finger(2, 1, 2), finger(8, 3, 4));
            f.state.handle(CANCEL, 0, Collections.emptyList(), false, VIDEO);
            Packet cancel = f.packets.get(2);
            check(cancel.type == MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, "cancel all");
            near(cancel.pressure, 0);
            check(cancel.rotation == MoonBridge.LI_ROT_UNKNOWN, "unknown rotation");
            check(f.state.activePointerCount() == 0, "all cleared");
            f.event(MOVE, 0, finger(2, 10, 20));
            check(f.packets.size() == 3, "no ghost move after cancel");
        });

        test("FLAG_CANCELED up cancels only its pointer", () -> {
            Fixture f = new Fixture();
            NativeTouchState.Pointer p = finger(4, 1, 2), q = finger(13, 3, 4);
            f.event(DOWN, 0, p);
            f.event(POINTER_DOWN, 1, p, q);
            f.state.handle(POINTER_UP, 0, Arrays.asList(p, q), true, VIDEO);
            check(f.packets.get(2).type == MoonBridge.LI_TOUCH_EVENT_CANCEL, "single cancel");
            check(f.packets.get(2).id == 4, "correct pointer");
            check(f.state.activePointerCount() == 1, "other contact preserved");
            f.event(UP, 0, q);
        });

        test("no two-finger cap: all 32 Android pointer IDs", () -> {
            Fixture f = new Fixture();
            List<NativeTouchState.Pointer> pointers = new ArrayList<>();
            for (int id = 0; id < 32; id++) {
                pointers.add(finger(id, id * 10, 10));
                f.state.handle(id == 0 ? DOWN : POINTER_DOWN, id, pointers, false, VIDEO);
            }
            check(f.state.activePointerCount() == 32, "all contacts accepted");
            f.state.handle(MOVE, 0, pointers, false, VIDEO);
            check(f.packets.size() == 64, "one MOVE per active contact");
            f.state.cancel();
            check(f.state.activePointerCount() == 0, "all contacts cleared");
        });

        test("background offsets and aspect-fitted video coordinates", () -> {
            Fixture f = new Fixture();
            f.state.handle(DOWN, 0, Collections.singletonList(finger(0, 600, 300)), false,
                    new NativeTouchState.Geometry(1000, 500, 100, 50));
            near(f.packets.get(0).x, 0.5f);
            near(f.packets.get(0).y, 0.5f);
        });

        test("out-of-surface coordinates clamp to protocol endpoints", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(0, -30, 800));
            near(f.packets.get(0).x, 0);
            near(f.packets.get(0).y, 1);
        });

        test("resolution independence", () -> {
            Fixture f = new Fixture();
            f.state.handle(DOWN, 0, Collections.singletonList(finger(5, 960, 540)), false,
                    new NativeTouchState.Geometry(1920, 1080, 0, 0));
            near(f.packets.get(0).x, 0.5f);
            near(f.packets.get(0).y, 0.5f);
        });

        test("pressure clamps and unknown metadata uses documented sentinels", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, new NativeTouchState.Pointer(0, 0, 0, 5, 0, 0, 0, false));
            near(f.packets.get(0).pressure, 1);
            near(f.packets.get(0).major, 0);
            near(f.packets.get(0).minor, 0);
            check(f.packets.get(0).rotation == MoonBridge.LI_ROT_UNKNOWN, "unknown rotation");
        });

        test("contact area normalization and negative orientation", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, new NativeTouchState.Pointer(0, 0, 0, 0.7f, 100, 20,
                    (float) -Math.PI / 2, true));
            near(f.packets.get(0).major, 0.2f);
            near(f.packets.get(0).minor, 0.02f);
            check(f.packets.get(0).rotation >= 269 && f.packets.get(0).rotation <= 271, "rotation in degrees");
        });

        test("missing minor axis uses a circular contact", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, new NativeTouchState.Pointer(0, 0, 0, 0, 50, 0, 0, false));
            near(f.packets.get(0).major, f.packets.get(0).minor);
        });

        test("zero-size surface neither divides by zero nor starts contacts", () -> {
            Fixture f = new Fixture();
            f.state.handle(DOWN, 0, Collections.singletonList(finger(0, 1, 2)), false,
                    new NativeTouchState.Geometry(0, 0, 0, 0));
            check(f.packets.isEmpty(), "no invalid packets");
            f.event(MOVE, 0, finger(0, 1, 2));
            check(f.packets.isEmpty(), "no implicit down on move");
        });

        test("surface disappearing cancels existing contacts", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(0, 1, 2));
            f.state.handle(MOVE, 0, Collections.singletonList(finger(0, 1, 2)), false,
                    new NativeTouchState.Geometry(0, 0, 0, 0));
            check(f.packets.get(1).type == MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, "cancel on invalid surface");
        });

        test("focus/grab/pause cancellation is idempotent", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(0, 1, 2));
            f.state.cancel();
            f.state.cancel();
            check(f.packets.size() == 2, "one cancel only");
            f.event(DOWN, 0, finger(0, 3, 4));
            f.event(UP, 0, finger(0, 3, 4));
            check(f.state.activePointerCount() == 0, "new manual gesture works");
        });

        test("duplicate DOWN suppressed and IDs can be reused after UP", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(7, 1, 2));
            f.event(POINTER_DOWN, 0, finger(7, 1, 2));
            check(f.packets.size() == 1, "duplicate suppressed");
            f.event(UP, 0, finger(7, 1, 2));
            f.event(DOWN, 0, finger(7, 1, 2));
            check(f.packets.size() == 3, "released ID reusable");
        });

        test("new gesture cancels orphaned old gesture", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, finger(7, 1, 2));
            f.event(DOWN, 0, finger(8, 3, 4));
            check(f.packets.get(1).type == MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, "stale contact canceled");
            check(f.state.activePointerCount() == 1, "new contact");
        });

        test("unsupported host never falls back or retries", () -> {
            Fixture f = new Fixture();
            f.result = MoonBridge.LI_ERR_UNSUPPORTED;
            f.event(DOWN, 0, finger(1, 1, 2));
            f.event(MOVE, 0, finger(1, 2, 3));
            f.event(UP, 0, finger(1, 2, 3));
            f.event(DOWN, 0, finger(1, 1, 2));
            check(f.packets.size() == 1, "no automatic retries or extra packets");
            check(f.errors.equals(Collections.singletonList(MoonBridge.LI_ERR_UNSUPPORTED)), "exact error");
            check(f.state.activePointerCount() == 0, "cleared state");
        });

        test("mid-gesture failure cancels once and stops before second MOVE", () -> {
            Fixture f = new Fixture();
            NativeTouchState.Pointer p = finger(1, 1, 2), q = finger(2, 3, 4);
            f.event(DOWN, 0, p);
            f.event(POINTER_DOWN, 1, p, q);
            f.result = -2;
            f.event(MOVE, 0, p, q);
            check(f.packets.size() == 4, "failed move plus one cleanup cancel only");
            check(f.packets.get(3).type == MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, "best effort remote cleanup");
            check(f.errors.equals(Arrays.asList(-2, -2)), "both send and cleanup failures visible");
            check(f.state.activePointerCount() == 0, "local state cleared");
        });

        test("non-finite sensor metadata cannot create invalid packet floats", () -> {
            Fixture f = new Fixture();
            f.event(DOWN, 0, new NativeTouchState.Pointer(0, 0, 0, Float.NaN,
                    Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, true));
            Packet packet = f.packets.get(0);
            near(packet.pressure, 0);
            near(packet.major, 0);
            near(packet.minor, 0);
            check(packet.rotation == MoonBridge.LI_ROT_UNKNOWN, "unknown orientation");
        });

        test("failed UP cancels the gesture and never releases another pointer by index", () -> {
            Fixture f = new Fixture();
            NativeTouchState.Pointer p = finger(11, 1, 2), q = finger(22, 3, 4);
            f.event(DOWN, 0, p);
            f.event(POINTER_DOWN, 1, p, q);
            f.result = -1;
            f.event(POINTER_UP, 0, q, p);
            check(f.packets.get(2).type == MoonBridge.LI_TOUCH_EVENT_UP, "failed UP packet");
            check(f.packets.get(2).id == 22, "Android ID, not index");
            check(f.packets.get(3).type == MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, "cleanup");
            check(f.state.activePointerCount() == 0, "no locally held contacts");
        });

        System.out.println("Passed " + tests + " native touchscreen state/geometry tests.");
    }
}

package me.decce.ixeris;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Manages input state between the polling thread (main thread) and the render thread.
 *
 * The polling thread calls {@link #drainEvents()} after each Display.processMessages()
 * to extract all Mouse/Keyboard events from LWJGL's internal queues and store them
 * in thread-safe buffers. The render thread then reads from these buffers instead
 * of touching LWJGL's non-thread-safe internal state.
 *
 * The ASM transformer redirects Mouse.* and Keyboard.* calls on the render thread
 * to the methods of this class.
 */
public class InputManager {

    // ---- Event storage ----

    private static final ConcurrentLinkedQueue<MouseEvent> mouseEvents = new ConcurrentLinkedQueue<MouseEvent>();
    private static final ConcurrentLinkedQueue<KeyboardEvent> keyboardEvents = new ConcurrentLinkedQueue<KeyboardEvent>();

    // "Current event" — set by next*() and read by getEvent*() on the render thread
    private static MouseEvent currentMouseEvent = null;
    private static KeyboardEvent currentKeyboardEvent = null;

    // ---- Cached absolute state (written by polling thread, read by render thread) ----

    private static volatile int mouseX = 0;
    private static volatile int mouseY = 0;
    private static volatile boolean mouseInsideWindow = true;

    // Accumulated deltas (polling thread adds, render thread reads-and-resets)
    private static final AtomicInteger accumDX = new AtomicInteger(0);
    private static final AtomicInteger accumDY = new AtomicInteger(0);
    private static final AtomicInteger accumDWheel = new AtomicInteger(0);

    // Button / key states
    private static final AtomicIntegerArray mouseButtonDown = new AtomicIntegerArray(16);
    private static final AtomicIntegerArray keyDown = new AtomicIntegerArray(256);

    // Mouse grab state (written by render thread via setGrabbed, read by render thread via isGrabbed)
    private static volatile boolean mouseGrabbed = false;

    // Static state that doesn't change after LWJGL init
    private static volatile int buttonCount = 0;
    private static volatile boolean hasWheel = true;

    // ---- Event data classes ----

    static class MouseEvent {
        int x, y, dx, dy, dWheel, button;
        boolean buttonState;
        long nanos;
    }

    static class KeyboardEvent {
        int key;
        char character;
        boolean keyState;
        boolean repeat;
        long nanos;
    }

    // ===================================================================
    //  Polling thread API — called after Display.processMessages()
    // ===================================================================

    /**
     * Drains all pending Mouse and Keyboard events from LWJGL's internal
     * queues into our thread-safe buffers. Must be called on the polling
     * thread (the same thread that called Display.processMessages()).
     */
    public static void drainEvents() {
        // Update static state once
        if (buttonCount == 0 && Mouse.isCreated()) {
            buttonCount = Mouse.getButtonCount();
            hasWheel = Mouse.hasWheel();
        }

        // Drain mouse events
        while (Mouse.next()) {
            MouseEvent event = new MouseEvent();
            event.x = Mouse.getEventX();
            event.y = Mouse.getEventY();
            event.dx = Mouse.getEventDX();
            event.dy = Mouse.getEventDY();
            event.dWheel = Mouse.getEventDWheel();
            event.button = Mouse.getEventButton();
            event.buttonState = Mouse.getEventButtonState();
            event.nanos = Mouse.getEventNanoseconds();
            mouseEvents.add(event);

            // Update button state cache
            if (event.button >= 0 && event.button < 16) {
                mouseButtonDown.set(event.button, event.buttonState ? 1 : 0);
            }
        }

        // Update current mouse position and accumulate deltas
        if (Mouse.isCreated()) {
            mouseX = Mouse.getX();
            mouseY = Mouse.getY();
            accumDX.addAndGet(Mouse.getDX());
            accumDY.addAndGet(Mouse.getDY());
            accumDWheel.addAndGet(Mouse.getDWheel());
            mouseInsideWindow = Mouse.isInsideWindow();
        }

        // Drain keyboard events
        while (Keyboard.next()) {
            KeyboardEvent event = new KeyboardEvent();
            event.key = Keyboard.getEventKey();
            event.character = Keyboard.getEventCharacter();
            event.keyState = Keyboard.getEventKeyState();
            event.nanos = Keyboard.getEventNanoseconds();
            event.repeat = Keyboard.isRepeatEvent();
            keyboardEvents.add(event);

            // Update key state cache
            if (event.key >= 0 && event.key < 256) {
                keyDown.set(event.key, event.keyState ? 1 : 0);
            }
        }
    }

    // ===================================================================
    //  Render thread API — redirected from Mouse.* and Keyboard.*
    // ===================================================================

    // ---- Mouse ----

    public static boolean mouseNext() {
        currentMouseEvent = mouseEvents.poll();
        return currentMouseEvent != null;
    }

    public static int getMouseEventX() {
        return currentMouseEvent != null ? currentMouseEvent.x : 0;
    }

    public static int getMouseEventY() {
        return currentMouseEvent != null ? currentMouseEvent.y : 0;
    }

    public static int getMouseEventDX() {
        return currentMouseEvent != null ? currentMouseEvent.dx : 0;
    }

    public static int getMouseEventDY() {
        return currentMouseEvent != null ? currentMouseEvent.dy : 0;
    }

    public static int getMouseEventDWheel() {
        return currentMouseEvent != null ? currentMouseEvent.dWheel : 0;
    }

    public static int getMouseEventButton() {
        return currentMouseEvent != null ? currentMouseEvent.button : -1;
    }

    public static boolean getMouseEventButtonState() {
        return currentMouseEvent != null && currentMouseEvent.buttonState;
    }

    public static long getMouseEventNanoseconds() {
        return currentMouseEvent != null ? currentMouseEvent.nanos : 0L;
    }

    public static boolean isMouseButtonDown(int button) {
        if (button >= 0 && button < 16) {
            return mouseButtonDown.get(button) != 0;
        }
        return false;
    }

    public static int getMouseX() {
        return mouseX;
    }

    public static int getMouseY() {
        return mouseY;
    }

    public static int getMouseDX() {
        return accumDX.getAndSet(0);
    }

    public static int getMouseDY() {
        return accumDY.getAndSet(0);
    }

    public static int getMouseDWheel() {
        return accumDWheel.getAndSet(0);
    }

    public static int getMouseButtonCount() {
        return buttonCount;
    }

    public static boolean mouseHasWheel() {
        return hasWheel;
    }

    public static boolean isMouseGrabbed() {
        return mouseGrabbed;
    }

    public static void setMouseGrabbed(boolean grab) {
        mouseGrabbed = grab;
    }

    public static boolean isMouseInsideWindow() {
        return mouseInsideWindow;
    }

    // ---- Keyboard ----

    public static boolean keyboardNext() {
        currentKeyboardEvent = keyboardEvents.poll();
        return currentKeyboardEvent != null;
    }

    public static int getKeyboardEventKey() {
        return currentKeyboardEvent != null ? currentKeyboardEvent.key : 0;
    }

    public static char getKeyboardEventCharacter() {
        return currentKeyboardEvent != null ? currentKeyboardEvent.character : '\0';
    }

    public static boolean getKeyboardEventKeyState() {
        return currentKeyboardEvent != null && currentKeyboardEvent.keyState;
    }

    public static long getKeyboardEventNanoseconds() {
        return currentKeyboardEvent != null ? currentKeyboardEvent.nanos : 0L;
    }

    public static boolean isKeyboardRepeatEvent() {
        return currentKeyboardEvent != null && currentKeyboardEvent.repeat;
    }

    public static boolean isKeyDown(int key) {
        if (key >= 0 && key < 256) {
            return keyDown.get(key) != 0;
        }
        return false;
    }
}

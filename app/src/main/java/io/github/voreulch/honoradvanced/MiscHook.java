package io.github.voreulch.honoradvanced;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 杂项 hooks — SystemUI line (v2.0.1).
 *
 * 屏蔽通知回避 (block notification ducking): notification sounds are played
 * by SystemUI, which first requests audio focus with
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK — MediaFocusControl then ducks (attenuates
 * the running media player via VolumeShaper 1.0 -> 0.2). We hook
 * AudioManager.requestAudioFocus INSIDE SystemUI and answer FAILED for
 * notification-family MAY_DUCK requests: the ringtone itself plays regardless
 * of the focus result (focus is only a coordination hint), while
 * MediaFocusControl never hears about it and the music never gets ducked —
 * notification sound and music play together.
 *
 * Why SystemUI and not system_server: the notification sound's focus request
 * originates here (dumpsys: callingPack=com.android.systemui), and in this
 * process the module's Cfg snapshot works normally (same channel as the
 * glass switches). In system_server the config is unreadable (SELinux hides
 * app data from it, ENOENT; the provider preload hangs there — a latent
 * 1.4.x issue unmasked by isOnStrict), so hooks there can never see the
 * switch state. Alarms (USAGE_ALARM) and exclusive requests never match and
 * keep working normally.
 *
 * Signature-agnostic: every requestAudioFocus overload of the framework
 * AudioManager is hooked; args are scanned at call time for the focus-gain
 * int (or AudioFocusRequest wrapper) plus the AudioAttributes / legacy
 * stream markers of the notification family.
 */
public final class MiscHook {

    private static final String TAG = "[HAS-Misc] ";
    private static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3;
    // AudioManager.AUDIOFOCUS_REQUEST_FAILED = 0 (GRANTED = 1)
    private static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    // android.media.AudioAttributes usage constants (AOSP values — the
    // notification family is 5..10; 2 is VOICE_COMMUNICATION, NOT one of us)
    private static final int USAGE_NOTIFICATION = 5;
    private static final int USAGE_NOTIFICATION_RINGTONE = 6;
    private static final int USAGE_NOTIFICATION_COMMUNICATION_REQUEST = 7;
    private static final int USAGE_NOTIFICATION_COMMUNICATION_INSTANT = 8;
    private static final int USAGE_NOTIFICATION_COMMUNICATION_DELAYED = 9;
    private static final int USAGE_NOTIFICATION_EVENT = 10;
    // legacy stream types (pre-attributes request path)
    private static final int STREAM_RING = 2;
    private static final int STREAM_NOTIFICATION = 5;

    private MiscHook() {
    }

    /** called from MainHook for the SystemUI process only. */
    public static void initSystemUI(ClassLoader cl) {
        try {
            Class<?> am = cl.loadClass("android.media.AudioManager");
            int n = 0;
            for (final Method m : am.getDeclaredMethods()) {
                if (!m.getName().startsWith("requestAudioFocus")) continue;
                final String name = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Cfg.isOn(Cfg.K_NODUCK)) return;
                            if (isNotificationDuckRequest(expandArgs(param.args))) {
                                // answer FAILED: the notification player does
                                // not depend on the focus result, it keeps
                                // playing — but the music never gets ducked
                                param.setResult(AUDIOFOCUS_REQUEST_FAILED);
                                XposedBridge.log(TAG + "notification duck request rejected (" + name + ")");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "noduck error: " + t);
                        }
                    }
                });
                n++;
            }
            XposedBridge.log(TAG + "hooked AudioManager.requestAudioFocus* overloads: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook AudioManager failed: " + t);
        }
    }

    /** flatten AudioFocusRequest objects into their gain int + attributes,
     *  so the signature-agnostic arg scan can see through the wrapper. */
    private static Object[] expandArgs(Object[] args) {
        if (args == null) return null;
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(args.length + 4);
        for (Object a : args) {
            out.add(a);
            if (a != null && "android.media.AudioFocusRequest".equals(a.getClass().getName())) {
                try {
                    out.add(a.getClass().getMethod("getFocusGain").invoke(a));
                    out.add(a.getClass().getMethod("getAudioAttributes").invoke(a));
                } catch (Throwable ignored) {
                }
            }
        }
        return out.toArray();
    }

    /**
     * true when the intercepted focus request wants MAY_DUCK and comes from
     * the notification family. Scans the args of any overload:
     *   - an int arg equal to MAY_DUCK marks the focus gain
     *   - an AudioAttributes arg with a notification usage, or a legacy
     *     STREAM_NOTIFICATION / STREAM_RING int, marks the source
     * Alarms (USAGE_ALARM) and exclusive requests never match — calls and
     * alarms keep pausing/ducking media exactly as before.
     */
    private static boolean isNotificationDuckRequest(Object[] args) {
        if (args == null) return false;
        boolean mayDuck = false;
        boolean notifSource = false;
        for (Object a : args) {
            if (a instanceof Integer) {
                int v = (Integer) a;
                if (v == AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                    mayDuck = true;
                } else if (v == STREAM_RING || v == STREAM_NOTIFICATION) {
                    // legacy request path: the stream type marks the source
                    notifSource = true;
                }
            } else if (a != null && "android.media.AudioAttributes".equals(a.getClass().getName())) {
                try {
                    int usage = (Integer) a.getClass().getMethod("getUsage").invoke(a);
                    if (usage == USAGE_NOTIFICATION
                            || usage == USAGE_NOTIFICATION_RINGTONE
                            || usage == USAGE_NOTIFICATION_COMMUNICATION_REQUEST
                            || usage == USAGE_NOTIFICATION_COMMUNICATION_INSTANT
                            || usage == USAGE_NOTIFICATION_COMMUNICATION_DELAYED
                            || usage == USAGE_NOTIFICATION_EVENT) {
                        notifSource = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return mayDuck && notifSource;
    }
}

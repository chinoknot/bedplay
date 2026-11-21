package app.bedplay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

/** Single screen: a full-screen WebView showing the local page; the gamepad state is pushed into the page. */
public class MainActivity extends Activity {
    private WebView web;
    private ProxyServer proxy;
    private volatile boolean streaming;

    // gamepad state ("standard" Gamepad API mapping)
    private final float[] btn = new float[17];
    private final float[] axes = new float[4];
    private long lastPush;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            proxy = new ProxyServer(this, new Auth(this));
            proxy.start();
        } catch (Exception e) {
            // port 18080 is taken (e.g. another copy of the app is still running): show a clear message
            TextView t = new TextView(this);
            t.setText("BedPlay: local port " + ProxyServer.PORT + " is in use. Close the other instance and try again.");
            t.setTextColor(Color.WHITE);
            t.setBackgroundColor(Color.BLACK);
            t.setTextSize(24);
            t.setPadding(80, 80, 80, 80);
            setContentView(t);
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setFocusable(true);
        web.requestFocus();
        web.loadUrl("http://127.0.0.1:" + ProxyServer.PORT + "/?k=" + proxy.key);
        hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
    }

    @Override
    protected void onDestroy() {
        if (proxy != null) proxy.stop();
        super.onDestroy();
    }

    /** Functions the page can call. */
    private class Bridge {
        @JavascriptInterface
        public void streaming(boolean on) {
            streaming = on;
            if (!on) {
                java.util.Arrays.fill(btn, 0f);
                java.util.Arrays.fill(axes, 0f);
            }
        }

        @JavascriptInterface
        public void quit() {
            runOnUiThread(MainActivity.this::finish);
        }
    }

    // ---- controller -------------------------------------------------------

    private static boolean isPad(InputDevice d) {
        if (d == null) return false;
        int src = d.getSources();
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static int buttonIndex(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_B: return 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 3;
            case KeyEvent.KEYCODE_BUTTON_L1: return 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 5;
            case KeyEvent.KEYCODE_BUTTON_L2: return 6;
            case KeyEvent.KEYCODE_BUTTON_R2: return 7;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 8;
            case KeyEvent.KEYCODE_BUTTON_START: return 9;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 10;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 11;
            case KeyEvent.KEYCODE_DPAD_UP: return 12;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 13;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 14;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 15;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 16;
            default: return -1;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (streaming && isPad(e.getDevice())) {
            int i = buttonIndex(e.getKeyCode());
            if (i >= 0) {
                btn[i] = e.getAction() == KeyEvent.ACTION_DOWN ? 1f : 0f;
                push(true);
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return true; // the pad B button must not close the app
        }
        if (streaming && e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            web.evaluateJavascript("window.__back && window.__back()", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (streaming && isPad(e.getDevice()) && (e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0
                && e.getAction() == MotionEvent.ACTION_MOVE) {
            axes[0] = dead(e.getAxisValue(MotionEvent.AXIS_X));
            axes[1] = dead(e.getAxisValue(MotionEvent.AXIS_Y));
            axes[2] = dead(e.getAxisValue(MotionEvent.AXIS_Z));
            axes[3] = dead(e.getAxisValue(MotionEvent.AXIS_RZ));
            float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            btn[6] = lt;
            btn[7] = rt;
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            btn[14] = hx < -0.5f ? 1f : 0f;
            btn[15] = hx > 0.5f ? 1f : 0f;
            btn[12] = hy < -0.5f ? 1f : 0f;
            btn[13] = hy > 0.5f ? 1f : 0f;
            push(false);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static float dead(float v) {
        return Math.abs(v) < 0.08f ? 0f : v;
    }

    /** Pushes the state to the page; axis events (very frequent) are limited to ~120 per second. */
    private void push(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastPush < 8) return;
        lastPush = now;
        StringBuilder sb = new StringBuilder("window.__pad&&window.__pad([");
        for (int i = 0; i < btn.length; i++) sb.append(i > 0 ? "," : "").append(btn[i]);
        sb.append("],[");
        for (int i = 0; i < axes.length; i++) sb.append(i > 0 ? "," : "").append(axes[i]);
        sb.append("])");
        final String js = sb.toString();
        web.post(() -> web.evaluateJavascript(js, null));
    }
}

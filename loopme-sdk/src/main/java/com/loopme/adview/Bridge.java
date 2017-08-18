package com.loopme.adview;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Vibrator;
import android.text.TextUtils;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.loopme.common.Logging;
import com.loopme.debugging.ErrorLog;
import com.loopme.debugging.ErrorType;

import java.net.URI;
import java.net.URISyntaxException;

public class Bridge extends WebViewClient {

    private static final String LOG_TAG = Bridge.class.getSimpleName();

    private static final String LOOPME = "loopme";
    private static final String WEBVIEW = "webview";
    private static final String VIDEO = "video";

    private static final String WEBVIEW_CLOSE = "/close";
    private static final String WEBVIEW_FAIL = "/fail";
    private static final String WEBVIEW_SUCCESS = "/success";
    private static final String WEBVIEW_VIBRATE = "/vibrate";
    private static final String WEBVIEW_FULLSCREEN = "/fullscreenMode";

    private static final String VIDEO_LOAD = "/load";
    private static final String VIDEO_MUTE = "/mute";
    private static final String VIDEO_PLAY = "/play";
    private static final String VIDEO_PAUSE = "/pause";
    private static final String VIDEO_ENABLE_STRETCH = "/enableStretching";
    private static final String VIDEO_DISABLE_STRETCH = "/disableStretching";

    private static final String QUERY_PARAM_SRC = "src";
    private static final String QUERY_PARAM_CURRENT_TIME = "currentTime";
    private static final String QUERY_PARAM_MUTE = "mute";
    private static final String QUERY_PARAM_FULLSCREEN_MODE = "mode";

    private static final int VIBRATE_MILLISECONDS = 500;

    private Listener mListener;
    private Context mContext;

    public interface Listener {

        void onJsClose();

        void onJsLoadSuccess();

        void onJsLoadFail(String mess);

        void onJsFullscreenMode(boolean isFullScreen);

        void onJsVideoLoad(String videoUrl);

        void onJsVideoMute(boolean mute);

        void onJsVideoPlay(int time);

        void onJsVideoPause(int time);

        void onJsVideoStretch(boolean b);

        void onNonLoopMe(String url);

        void onCreateMoatNativeTracker();

        void onCreateMoatWebAdTracker();

        void onLeaveApp();

    }

    public Bridge(Listener listener, Context context) {
        if (listener != null  && context!=null) {
            mListener = listener;
            mContext = context;
        } else {
            Logging.out(LOG_TAG, "VideoBridgeListener should not be null");
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        Logging.out(LOG_TAG, "shouldOverrideUrlLoading " + url);

        if (TextUtils.isEmpty(url)) {
            ErrorLog.post("Broken redirect in bridge: " + url, ErrorType.JS);
            return false;
        }

        Context context = view.getContext();
        URI redirect = null;
        try {
            redirect = new URI(url);
        } catch (URISyntaxException e) {
            Logging.out(LOG_TAG, e.getMessage());
            e.printStackTrace();
            ErrorLog.post("Broken redirect in bridge: " + url, ErrorType.JS);
            handleExtraUrl(url);
            return true;
        }

        String protocol = redirect.getScheme();
        if (TextUtils.isEmpty(protocol)) {
            return false;
        }

        if (protocol.equalsIgnoreCase(LOOPME)) {
            ((AdView) view).sendNativeCallFinished();
            String host = redirect.getHost();
            String path = redirect.getPath();
            if (TextUtils.isEmpty(host) || TextUtils.isEmpty(path)) {
                return false;
            }

            if (host.equalsIgnoreCase(WEBVIEW)) {
                handleWebviewCommands(path, url, context);
            } else if (host.equalsIgnoreCase(VIDEO)) {
                handleVideoCommands(path, url);
            }
        } else {
            if (mListener != null) {
                mListener.onNonLoopMe(url);
            }
        }

        return true;
    }

    private void handleExtraUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startActivity(intent)) {
            onLeaveApp();
        }
    }

    private void onLeaveApp() {
        if (mListener != null) {
            mListener.onLeaveApp();
        }
    }

    private boolean startActivity(Intent intent) {
        try {
            if (mContext != null) {
                mContext.startActivity(intent);
                return true;
            }
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void handleWebviewCommands(String command, String url, Context context) {
        if (command == null || mListener == null) {
            return;
        }
        switch (command) {
            case WEBVIEW_CLOSE:
                mListener.onJsClose();
                break;

            case WEBVIEW_VIBRATE:
                handleVibrate(context);
                break;

            case WEBVIEW_FAIL:
                mListener.onJsLoadFail("Ad received specific URL loopme://webview/fail");
                break;

            case WEBVIEW_FULLSCREEN:
                handleFullscreenMode(url);
                break;
            case WEBVIEW_SUCCESS: {
                mListener.onJsLoadSuccess();
                onCreateMoatWebAdTracker();
                break;
            }

            default:
                break;
        }
    }

    private void handleFullscreenMode(String url) {
        try {
            Uri uri = Uri.parse(url);
            String modeStr = detectQueryParameter(uri, QUERY_PARAM_FULLSCREEN_MODE);
            if (!isValidBooleanParameter(modeStr)) {
                ErrorLog.post("Empty parameter in js command: fullscreen mode", ErrorType.JS);

            } else {
                mListener.onJsFullscreenMode(Boolean.parseBoolean(modeStr));
            }
        } catch (NullPointerException | UnsupportedOperationException e) {
            e.printStackTrace();
        }
    }

    private boolean isValidBooleanParameter(String modeStr) {
        return !TextUtils.isEmpty(modeStr) &&
                (modeStr.equalsIgnoreCase(Boolean.TRUE.toString()) ||
                        modeStr.equalsIgnoreCase(Boolean.FALSE.toString()));
    }

    private void handleVibrate(Context context) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(VIBRATE_MILLISECONDS);
            }
        } catch (Exception e) {
            Logging.out(LOG_TAG, "Missing permission for vibrate");
        }
    }

    private void handleVideoCommands(String command, String url) {

        if (command == null || mListener == null) {
            return;
        }

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (NullPointerException e) {
            e.printStackTrace();
            return;
        }

        switch (command) {
            case VIDEO_LOAD:
                onCreateMoatNativeTracker();
                String videoUrl = detectQueryParameter(uri, QUERY_PARAM_SRC);
                if (!TextUtils.isEmpty(videoUrl)) {
                    mListener.onJsVideoLoad(videoUrl);
                } else {
                    ErrorLog.post("Empty parameter in js command: src", ErrorType.JS);
                }
                break;

            case VIDEO_MUTE:
                String muteStr = detectQueryParameter(uri, QUERY_PARAM_MUTE);
                if (isValidBooleanParameter(muteStr)) {
                    mListener.onJsVideoMute(Boolean.parseBoolean(muteStr));
                } else {
                    ErrorLog.post("Empty parameter in js command: mute", ErrorType.JS);
                }
                break;

            case VIDEO_PLAY:
                String playStr = detectQueryParameter(uri, QUERY_PARAM_CURRENT_TIME);
                int time = 0;
                if (playStr != null) {
                    time = Integer.parseInt(playStr);
                }
                mListener.onJsVideoPlay(time);
                break;

            case VIDEO_PAUSE:
                String pauseStr = detectQueryParameter(uri, QUERY_PARAM_CURRENT_TIME);
                int pause_time = 0;
                if (pauseStr != null) {
                    pause_time = Integer.parseInt(pauseStr);
                }
                mListener.onJsVideoPause(pause_time);
                break;

            case VIDEO_ENABLE_STRETCH:
                mListener.onJsVideoStretch(true);
                break;

            case VIDEO_DISABLE_STRETCH:
                mListener.onJsVideoStretch(false);
                break;

            default:
                break;
        }
    }

    private String detectQueryParameter(Uri uri, String parameter) {
        String result = null;
        try {
            result = uri.getQueryParameter(parameter);
        } catch (NullPointerException | UnsupportedOperationException e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        if (mListener != null) {
            mListener.onJsLoadFail("onReceivedError " + description);
        }
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        Logging.out(LOG_TAG, "onPageStarted");
        super.onPageStarted(view, url, favicon);
    }

    @Override
    public void onPageFinished(WebView webView, String url) {
        Logging.out(LOG_TAG, "onPageFinished");
        super.onPageFinished(webView, url);
    }

    private void onCreateMoatNativeTracker() {
        if (mListener != null) {
            mListener.onCreateMoatNativeTracker();
        }
    }

    private void onCreateMoatWebAdTracker() {
        if (mListener != null) {
            mListener.onCreateMoatWebAdTracker();
        }
    }
}

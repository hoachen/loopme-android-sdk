package com.loopme;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.loopme.common.AdFetcherTimer;
import com.loopme.common.AdParams;
import com.loopme.common.EventManager;
import com.loopme.common.ExecutorHelper;
import com.loopme.common.ExpirationTimer;
import com.loopme.common.Logging;
import com.loopme.common.LoopMeError;
import com.loopme.common.StaticParams;
import com.loopme.common.Utils;
import com.loopme.constants.AdFormat;
import com.loopme.constants.AdState;
import com.loopme.debugging.ErrorLog;
import com.loopme.debugging.ErrorType;
import com.loopme.request.AdRequestParametersProvider;
import com.loopme.request.AdRequestUrlBuilder;
import com.loopme.request.AdTargeting;
import com.loopme.request.AdTargetingData;
import com.loopme.tasks.AdFetcher;
import com.loopme.tasks.AdvIdFetcher;
import com.loopme.tasks.RequestTimer;
import com.moat.analytics.mobile.loo.MoatAnalytics;
import com.moat.analytics.mobile.loo.MoatOptions;

import java.util.List;
import java.util.concurrent.Future;

public abstract class BaseAd implements AdTargeting {

    private static final String LOG_TAG = BaseAd.class.getSimpleName();

    private Activity mContext;
    private String mAppKey;

    protected volatile AdController mAdController;

    protected Future mFuture;
    protected String mRequestUrl;

    protected ExpirationTimer mExpirationTimer;
    protected ExpirationTimer.Listener mExpirationListener;

    protected AdFetcherTimer mFetcherTimer;
    protected AdFetcherTimer.Listener mFetcherTimerListener;

    protected volatile AdFetcher.Listener mAdFetcherListener;

    protected volatile int mAdState = AdState.NONE;

    protected volatile boolean mIsReady;

    protected long mAdLoadingTimer;
    protected int mShowWhenAdNotReadyCounter;

    private AdParams mAdParams;
    private AdTargetingData mAdTargetingData = new AdTargetingData();

    private RequestTimer mRequestTimer;
    private volatile RequestTimer.Listener mRequestTimerListener;
    private IntegrationType mIntegrationType = IntegrationType.NORMAL;

    protected Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mHtmlAd;
    private boolean mNativeAd;

    public BaseAd(Activity activity, String appKey) {
        if (activity == null || TextUtils.isEmpty(appKey)) {
            throw new IllegalArgumentException("Wrong parameters");
        }
        mContext = activity;
        mAppKey = appKey;
        AdRequestParametersProvider.getInstance().init(this);
        Utils.setCacheDirectory(activity);
        initMoatAnalytics();
    }

    private void initMoatAnalytics() {
        MoatOptions options = new MoatOptions();
        options.disableAdIdCollection = true;
        MoatAnalytics.getInstance().start(options, mContext.getApplication());
    }

    /**
     * Indicates whether ad content was loaded successfully and ready to be displayed.
     * After you initialized a `LoopMeInterstitial`/`LoopMeBanner` object and triggered the `load` method,
     * this property will be set to TRUE on it's successful completion.
     * It is set to FALSE when loaded ad content has expired or already was presented,
     * in this case it requires next `load` method triggering
     */
    public boolean isReady() {
        return mIsReady;
    }

    /**
     * Indicates whether `LoopMeInterstitial`/`LoopMeBanner` currently presented on screen.
     * Ad status will be set to `AdState.SHOWING` after trigger `show` method
     *
     * @return true - if ad presented on screen
     * false - if ad absent on scrren
     */
    public boolean isShowing() {
        return mAdState == AdState.SHOWING;
    }

    /**
     * Indicates whether `LoopMeInterstitial`/`LoopMeBanner` in "loading ad content" process.
     * Ad status will be set to `AdState.LOADING` after trigger `load` method
     *
     * @return true - if ad is loading now
     * false - if ad is not loading now
     */
    public boolean isLoading() {
        return mAdState == AdState.LOADING;
    }


    public void load() {
        load(IntegrationType.NORMAL);
    }

    /**
     * Starts loading ad content process.
     * It is recommended triggering it in advance to have interstitial/banner ad ready and to be able to display instantly in your
     * application.
     * After its execution, the interstitial/banner notifies whether the loading of the ad content failed or succeded.
     */
    public void load(IntegrationType integrationType) {
        Logging.out(LOG_TAG, "Start loading ad with app key " + mAppKey);
        if (mAdState == AdState.LOADING || mAdState == AdState.SHOWING) {
            Logging.out(LOG_TAG, "Ad already loading or showing");
            return;
        }
        mIntegrationType = integrationType != null ? integrationType : IntegrationType.NORMAL;

        if (mAdController == null) {
            mAdController = new AdController(this);
        }

        mAdState = AdState.LOADING;
        mAdLoadingTimer = System.currentTimeMillis();
        startFetcherTimer();

        if (isReady()) {
            Logging.out(LOG_TAG, "Ad already loaded");
            onAdLoadSuccess();
            return;
        }

        if (Build.VERSION.SDK_INT < 19) {
            onAdLoadFail(new LoopMeError("Not supported Android version. Expected Android 4.4+"));
            return;
        }

        if (Utils.isOnline(getContext())) {
            proceedLoad();
        } else {
            onAdLoadFail(new LoopMeError("No connection"));
        }
    }

    /**
     * Destroy to clean-up resources if Ad no longer needed
     * If the Ad is in "loading ad" phase, destroy causes it's interrupting and cleaning-up all related resources
     * If the Ad is in "displaying ad" phase, destroy causes "closing ad" and cleaning-up all related resources
     */
    public void destroy() {
        Logging.out(LOG_TAG, "Ad will be destroyed");

        mAdFetcherListener = null;
        mIsReady = false;
        stopExpirationTimer();
        stopFetcherTimer();
        mAdState = AdState.NONE;
        getAdTargetingData().clear();
        AdRequestParametersProvider.getInstance().reset();
        releaseViewController();

        if (getAdFormat() == AdFormat.INTERSTITIAL) {
            LoopMeAdHolder.removeInterstitial(mAppKey);
        } else {
            LoopMeAdHolder.removeBanner(mAppKey);
        }

        if (mFuture != null) {
            mFuture.cancel(true);
        }
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    protected void cancelFetcher() {
        Logging.out(LOG_TAG, "Cancel ad fether");

        mAdFetcherListener = null;
        releaseViewController();

        if (mFuture != null) {
            mFuture.cancel(true);
        }
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    public abstract int getAdFormat();

    public abstract void dismiss();

    abstract void onAdExpired();

    abstract void onAdLoadSuccess();

    abstract void onAdLoadFail(LoopMeError errorCode);

    abstract void onAdLeaveApp();

    abstract void onAdClicked();

    abstract void onAdVideoDidReachEnd();

    abstract int detectWidth();

    abstract int detectHeight();

    /**
     * The appKey uniquely identifies your app to the LoopMe ad network.
     * To get an appKey visit the LoopMe Dashboard.
     */
    public String getAppKey() {
        return mAppKey;
    }

    public Context getContext() {
        return mContext;
    }

    protected AdTargetingData getAdTargetingData() {
        return mAdTargetingData;
    }

    public AdParams getAdParams() {
        return mAdParams;
    }

    protected void setAdParams(AdParams params) {
        mAdParams = params;
    }

    protected void fetchAdComplete(AdParams params) {
        setAdParams(params);
        preloadHtmlContent(params.getHtml(), params.isMraid());
    }

    private void preloadHtmlContent(String html, boolean mraid) {
        if (TextUtils.isEmpty(html)) {
            onAdLoadFail(new LoopMeError("Broken response"));
            ErrorLog.post("Broken response (empty html)", ErrorType.SERVER);
            return;
        } else {
            if (mAdController != null) {
                mAdController.initControllers(mraid);
                mAdController.preloadHtml(html, mraid);

            } else {
                onAdLoadFail(new LoopMeError("Html loading error"));
            }
        }
    }

    protected AdFetcher.Listener initAdFetcherListener() {
        return new AdFetcher.Listener() {

            @Override
            public void onComplete(final AdParams params,
                                   final LoopMeError error) {

                stopRequestTimer();
                if (params != null && !params.getPackageIds().isEmpty()) {
                    List<String> installedPackages = Utils.getPackageInstalled(params.getPackageIds());
                    if (installedPackages != null && installedPackages.size() > 0) {
//                        mAdState = AdState.NONE;
//                        completeRequest(null, new LoopMeError("No valid ads found"));
                        completeRequest(params, error);

                        EventManager eventManager = new EventManager();
                        eventManager.trackSdkEvent(params.getToken());
                    } else {
                        completeRequest(params, error);
                    }
                } else {
                    completeRequest(params, error);
                }
            }
        };
    }

    private void completeRequest(final AdParams params, final LoopMeError error) {

        mHandler.post(new Runnable() {

            @Override
            public void run() {
                if (params == null) {
                    if (error != null) {
                        onAdLoadFail(error);
                    } else {
                        onAdLoadFail(new LoopMeError("Request timeout"));
                    }
                } else {
                    fetchAdComplete(params);
                }
            }
        });
    }

    private void proceedLoad() {
        if (AdRequestParametersProvider.getInstance().getGoogleAdvertisingId() == null) {
            Logging.out(LOG_TAG, "Start initialization google adv id");

            detectGoogleAdvertisingId();

        } else {
            fetchAd();
        }
    }

    private void detectGoogleAdvertisingId() {
        AdvIdFetcher advTask = new AdvIdFetcher(mContext, new AdvIdFetcher.Listener() {

            @Override
            public void onComplete(String advId, boolean isLimited) {
                AdRequestParametersProvider.getInstance().setGoogleAdvertisingId(advId, isLimited);
                fetchAd();
            }
        });
        mFuture = ExecutorHelper.getExecutor().submit(advTask);
    }

    protected void releaseViewController() {
        Logging.out(LOG_TAG, "Release ViewController");

        if (mAdController != null) {
            mAdController.destroy();
            mAdController = null;
        }
    }

    protected void startExpirationTimer() {
        if (mExpirationTimer != null || mAdParams == null ||
                mAdController == null || !mAdController.isVideoPresented()) {
            return;
        }
        int validTime = mAdParams.getExpiredTime();
        mExpirationListener = new ExpirationTimer.Listener() {

            @Override
            public void onExpired() {
                onAdExpired();
            }
        };
        mExpirationTimer = new ExpirationTimer(validTime, mExpirationListener);
        mExpirationTimer.start();
    }

    protected void stopExpirationTimer() {
        if (mExpirationTimer != null) {
            Logging.out(LOG_TAG, "Stop schedule expiration");
            mExpirationTimer.cancel();
            mExpirationTimer = null;
        }
        mExpirationListener = null;
    }

    protected void startFetcherTimer() {
        if (mFetcherTimer != null) {
            return;
        }
        mFetcherTimerListener = new AdFetcherTimer.Listener() {

            @Override
            public void onTimeout() {
                cancelFetcher();
                onAdLoadFail(new LoopMeError("Ad processing timeout"));
            }
        };
        mFetcherTimer = new AdFetcherTimer(StaticParams.FETCH_TIMEOUT,
                mFetcherTimerListener);
        float fetchTimeout = StaticParams.FETCH_TIMEOUT / (1000 * 60);
        Logging.out(LOG_TAG, "Fetch timeout: " + fetchTimeout + " minutes");
        mFetcherTimer.start();
    }

    protected void stopFetcherTimer() {
        Logging.out(LOG_TAG, "Stop fetcher timer");
        if (mFetcherTimer != null) {
            mFetcherTimer.cancel();
            mFetcherTimer = null;
        }
        mFetcherTimerListener = null;
    }

    protected void fetchAd() {
        LoopMeAdHolder.putAd(this);
        AdRequestParametersProvider.getInstance().setScreenSize();
        AdRequestParametersProvider.getInstance().setAdSize(this);
        mRequestUrl = new AdRequestUrlBuilder(mContext).buildRequestUrl(mAppKey, mAdTargetingData, mIntegrationType);
        if (mRequestUrl == null) {
            onAdLoadFail(new LoopMeError("Error during building ad request url"));
            return;
        }

        mAdFetcherListener = initAdFetcherListener();
        AdFetcher fetcher = new AdFetcher(mRequestUrl, mAdFetcherListener, getAdFormat());
        startRequestTimer();
        mFuture = ExecutorHelper.getExecutor().submit(fetcher);
    }

    /**
     * Changes default value of time interval during which video file will be cached.
     * Default time interval is 32 hours.
     */
    public void setVideoCacheTimeInterval(long milliseconds) {
        if (milliseconds > 0) {
            StaticParams.CACHED_VIDEO_LIFE_TIME = milliseconds;
        }
    }

    /**
     * Defines, should use mobile network for caching video or not.
     * By default, video will not cache on mobile network (only on wi-fi)
     *
     * @param b - true if need to cache video on mobile network,
     *          false if need to cache video only on wi-fi network.
     */
    public void useMobileNetworkForCaching(boolean b) {
        StaticParams.USE_MOBILE_NETWORK_FOR_CACHING = b;
    }

    /**
     * Use it for figure out any problems during integration process.
     * We recommend to set it "false" after full integration and testing.
     * <p>
     * If true - all debug logs will be in Logcat.
     * If false - only main info logs will be in Logcat.
     */
    public void setDebugMode(boolean mode) {
        StaticParams.DEBUG_MODE = mode;
    }

    @Override
    public void setKeywords(String keywords) {
        mAdTargetingData.setKeywords(keywords);
    }

    @Override
    public void setGender(String gender) {
        mAdTargetingData.setGender(gender);
    }

    @Override
    public void setYearOfBirth(int year) {
        mAdTargetingData.setYob(year);
    }

    @Override
    public void addCustomParameter(String param, String paramValue) {
        mAdTargetingData.setCustomParameters(param, paramValue);
    }

    public AdController getAdController() {
        return mAdController;
    }

    private void startRequestTimer() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mRequestTimerListener = initTimerListener();
                mRequestTimer = new RequestTimer(StaticParams.REQUEST_TIMEOUT, mRequestTimerListener);
                mRequestTimer.startTimer();
            }
        });
    }

    private void stopRequestTimer() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRequestTimer != null) {
                    mRequestTimer.stopTimer();
                    mRequestTimerListener = null;
                }
            }
        });
    }

    private RequestTimer.Listener initTimerListener() {
        return new RequestTimer.Listener() {

            @Override
            public void onTimeout() {
                if (mFuture != null && mAdFetcherListener != null) {
                    mAdFetcherListener = null;
                    mFuture.cancel(true);
                    mFuture = null;
                }
                onAdLoadFail(new LoopMeError("Request timeout"));
                ErrorLog.post("Request timeout", ErrorType.CUSTOM);
                ErrorLog.post("Request timeout", ErrorType.SERVER);
            }
        };
    }

    public boolean isHtmlAd() {
        return mHtmlAd;
    }

    public void setHtmlAd(boolean htmlAd) {
        this.mHtmlAd = htmlAd;
    }

    public boolean isNativeAd() {
        return mNativeAd;
    }

    public void setNativeAd(boolean nativeAd) {
        this.mNativeAd = nativeAd;
    }
}

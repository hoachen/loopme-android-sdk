package com.loopme.interstitial_sample;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Toast;

import com.loopme.common.LoopMeError;
import com.loopme.LoopMeInterstitial;

public class InterstitialSampleActivity extends AppCompatActivity implements LoopMeInterstitial.Listener {

    private LoopMeInterstitial mInterstitial;
    private ProgressDialog mProgressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initProgressDialog();
    }

    private void initProgressDialog() {
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setTitle("Loading");
        mProgressDialog.setMessage("Please wait");
        mProgressDialog.setCancelable(false);
    }

    public void onLoadClicked(View view) {
        // Create new interstitial object or get object from map
        // if it already presents
        mInterstitial = LoopMeInterstitial.getInstance(
                LoopMeInterstitial.TEST_PORT_INTERSTITIAL, this);

        if (mInterstitial != null) {
            mProgressDialog.show();

            /*
             * Adding listener to receive SDK notifications during the
            * loading/displaying ad processes
            */
            mInterstitial.setListener(this);

            // Start loading immediately
            mInterstitial.load();
        }
    }

    public void onShowClicked(View view) {
        // Checks whether ad ready to be shown
        if (mInterstitial != null && mInterstitial.isReady()) {
            // Show ad
            mInterstitial.show();
        } else {
            Toast.makeText(getApplicationContext(), "Interstitial is not ready", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.cancel();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.cancel();
        }
        if (mInterstitial != null) {
            // Clean up resources
            mInterstitial.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onLoopMeInterstitialLoadSuccess(LoopMeInterstitial interstitial) {
        mProgressDialog.dismiss();
    }

    @Override
    public void onLoopMeInterstitialLoadFail(LoopMeInterstitial interstitial,
                                             LoopMeError error) {
        mProgressDialog.dismiss();
        Toast.makeText(getApplicationContext(), error.getMessage(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLoopMeInterstitialShow(LoopMeInterstitial interstitial) {
    }

    @Override
    public void onLoopMeInterstitialHide(LoopMeInterstitial interstitial) {
    }

    @Override
    public void onLoopMeInterstitialClicked(LoopMeInterstitial interstitial) {
    }

    @Override
    public void onLoopMeInterstitialLeaveApp(LoopMeInterstitial interstitial) {
    }

    @Override
    public void onLoopMeInterstitialExpired(LoopMeInterstitial interstitial) {
    }

    @Override
    public void onLoopMeInterstitialVideoDidReachEnd(LoopMeInterstitial arg0) {
    }
}

/**
 * Copyright (C) 2017-2020 The LineageOS project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.internal.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;

import java.util.HashMap;

public class NetworkTraffic extends TextView {
    private static final String TAG = "NetworkTraffic";

    private static final boolean DEBUG = false;

    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;

    private static final int UNITS_KILOBITS = 0;
    private static final int UNITS_MEGABITS = 1;
    private static final int UNITS_KILOBYTES = 2;
    private static final int UNITS_MEGABYTES = 3;

    // Thresholds themselves are always defined in kbps
    private static final long AUTOHIDE_THRESHOLD_KILOBITS  = 10;
    private static final long AUTOHIDE_THRESHOLD_MEGABITS  = 100;
    private static final long AUTOHIDE_THRESHOLD_KILOBYTES = 8;
    private static final long AUTOHIDE_THRESHOLD_MEGABYTES = 80;

    protected static final String blank = "";

    protected int mLocation = 0;
    private int mMode = MODE_UPSTREAM_AND_DOWNSTREAM;
    private boolean mConnectionAvailable;
    protected boolean mIsActive;
    private long mTxKbps;
    private long mRxKbps;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastUpdateTime;
    private boolean mAutoHide;
    private long mAutoHideThreshold;
    private int mUnits;
    private boolean mShowUnits;
    protected int mIconTint = Color.WHITE;
    private SettingsObserver mObserver;
    private Drawable mDrawable;

    private int mRefreshInterval = 2;

    protected boolean mAttached;
    private boolean mHideArrows;

    // Used to indicate that the set of sources contributing
    // to current stats have changed.
    private boolean mNetworksChanged = true;

    private INetworkManagementService mNetworkManagementService;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            mContext.registerReceiver(mIntentReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            mObserver = new SettingsObserver(mTrafficHandler);
            mObserver.observe();
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mObserver.unobserve();
            mAttached = false;
        }
    }

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            final long now = SystemClock.elapsedRealtime();
            final long timeDelta = now - mLastUpdateTime; /* ms */
            if (msg.what == MESSAGE_TYPE_PERIODIC_REFRESH
                    && timeDelta >= mRefreshInterval * 1000 * 0.95f) {
                // Sum tx and rx bytes from all sources of interest
                long txBytes = 0;
                long rxBytes = 0;

                // Add stats
                final long newTxBytes = TrafficStats.getTotalTxBytes();
                final long newRxBytes = TrafficStats.getTotalRxBytes();

                txBytes += newTxBytes;
                rxBytes += newRxBytes;

                // Add tether hw offload counters since these are
                // not included in netd interface stats.
                final TetheringStats tetheringStats = getOffloadTetheringStats();
                txBytes += tetheringStats.txBytes;
                rxBytes += tetheringStats.rxBytes;

                if (DEBUG) {
                    Log.d(TAG, "mNetworksChanged = " + mNetworksChanged);
                    Log.d(TAG, "tether hw offload txBytes: " + tetheringStats.txBytes
                            + " rxBytes: " + tetheringStats.rxBytes);
                }

                final long txBytesDelta = txBytes - mLastTxBytes;
                final long rxBytesDelta = rxBytes - mLastRxBytes;

                if (!mNetworksChanged && timeDelta > 0 && txBytesDelta >= 0 && rxBytesDelta >= 0) {
                    mTxKbps = (long) (txBytesDelta * 8f / 1000f / (timeDelta / 1000f));
                    mRxKbps = (long) (rxBytesDelta * 8f / 1000f / (timeDelta / 1000f));
                } else if (mNetworksChanged) {
                    mTxKbps = 0;
                    mRxKbps = 0;
                    mNetworksChanged = false;
                }
                mLastTxBytes = txBytes;
                mLastRxBytes = rxBytes;
                mLastUpdateTime = now;
            }

            mConnectionAvailable = isConnectionAvailable();
            final boolean enabled = mLocation != 0;
            final boolean showUpstream =
                    mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean showDownstream =
                    mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean aboveThreshold = (showUpstream && mTxKbps > mAutoHideThreshold)
                    || (showDownstream && mRxKbps > mAutoHideThreshold);
            mIsActive = mAttached && (!mAutoHide || (mConnectionAvailable && aboveThreshold));

            if (enabled && mIsActive) {
                // Get information for uplink ready so the line return can be added
                StringBuilder output = new StringBuilder();
                if (showUpstream) {
                    output.append(formatOutput(mTxKbps));
                }

                // Ensure text size is where it needs to be
                if (showUpstream && showDownstream) {
                    output.append("\n");
                }

                // Add information for downlink if it's called for
                if (showDownstream) {
                    output.append(formatOutput(mRxKbps));
                }

                // Update view if there's anything new to show
                if (!output.toString().contentEquals(getText())) {
                    setText(output.toString());
                }
            }
            updateVisibility();

            // Schedule periodic refresh
            mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
            if (enabled && mAttached) {
                mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                        mRefreshInterval * 1000);
            }
        }

        private String formatOutput(long kbps) {
            final String value;
            final String unit;
            switch (mUnits) {
                case UNITS_KILOBITS:
                    value = String.format("%d", kbps);
                    unit = mContext.getString(R.string.kilobitspersecond_short);
                    break;
                case UNITS_MEGABITS:
                    value = String.format("%.1f", (float) kbps / 1000);
                    unit = mContext.getString(R.string.megabitspersecond_short);
                    break;
                case UNITS_KILOBYTES:
                    value = String.format("%d", kbps / 8);
                    unit = mContext.getString(R.string.kilobytespersecond_short);
                    break;
                case UNITS_MEGABYTES:
                    value = String.format("%.2f", (float) kbps / 8000);
                    unit = mContext.getString(R.string.megabytespersecond_short);
                    break;
                default:
                    value = "unknown";
                    unit = "unknown";
                    break;
            }

            if (mShowUnits) {
                return value + " " + unit;
            } else {
                return value;
            }
        }
    };

    protected void updateVisibility() {
        boolean enabled = mIsActive && !blank.contentEquals(getText()) && (mLocation == 2);
        if (enabled) {
            setVisibility(VISIBLE);
        } else {
            setText(blank);
            setVisibility(GONE);
        }
        updateTrafficDrawable(enabled);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                updateViewState();
            }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_LOCATION),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_REFRESH_INTERVAL),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_HIDEARROW),
                    false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private boolean isConnectionAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null;
    }

    private class TetheringStats {
        long txBytes;
        long rxBytes;
    }

    private TetheringStats getOffloadTetheringStats() {
        TetheringStats tetheringStats = new TetheringStats();

        NetworkStats stats = null;

        if (mNetworkManagementService == null) {
            mNetworkManagementService = INetworkManagementService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        }

        try {
            // STATS_PER_UID returns hw offload and netd stats combined (as entry UID_TETHERING)
            // STATS_PER_IFACE returns only hw offload stats (as entry UID_ALL)
            stats = mNetworkManagementService.getNetworkStatsTethering(
                    NetworkStats.STATS_PER_IFACE);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call getNetworkStatsTethering: " + e);
        }
        if (stats == null) {
            // nothing we can do except return zero stats
            return tetheringStats;
        }

        NetworkStats.Entry entry = null;
        // Entries here are per tethered interface.
        // Counters persist even after tethering has been disabled.
        for (int i = 0; i < stats.size(); i++) {
            entry = stats.getValues(i, entry);
            if (DEBUG) {
                Log.d(TAG, "tethering stats entry: " + entry);
            }
            // hw offload tether stats are reported under UID_ALL.
            if (entry.uid == NetworkStats.UID_ALL) {
                tetheringStats.txBytes += entry.txBytes;
                tetheringStats.rxBytes += entry.rxBytes;
            }
        }
        return tetheringStats;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mLocation = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_LOCATION, 0, UserHandle.USER_CURRENT);
        mMode = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_MODE, 0, UserHandle.USER_CURRENT);
        mAutoHide = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) != 0;
        mAutoHideThreshold = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 0, UserHandle.USER_CURRENT);
        mUnits = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_UNITS, /* Mbps */ 1,
                UserHandle.USER_CURRENT);

        mShowUnits = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS, 1,
                UserHandle.USER_CURRENT) == 1;
        mRefreshInterval = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_REFRESH_INTERVAL, 2, UserHandle.USER_CURRENT);
        mHideArrows = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_HIDEARROW, 0, UserHandle.USER_CURRENT) == 1;

        updateViewState();
    }

    private void updateViewState() {
        mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
    }

    protected void updateTrafficDrawable(boolean enabled) {
        final int drawableResId;
        final int textSizeId;
        final Resources resources = getResources();

        if (enabled && !mHideArrows && mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
            drawableResId = R.drawable.stat_sys_network_traffic_updown;
        } else if (enabled && !mHideArrows && mMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (enabled && !mHideArrows && mMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else {
            drawableResId = 0;
        }

        if (mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
            textSizeId = R.dimen.net_traffic_multi_text_size;
        } else {
            textSizeId = R.dimen.net_traffic_single_text_size;
        }

        mDrawable = drawableResId != 0 ? resources.getDrawable(drawableResId) : null;
        if (mDrawable != null) {
            mDrawable.setColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY);
        }
        setCompoundDrawablesWithIntrinsicBounds(null, null, mDrawable, null);

        setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) resources.getDimensionPixelSize(textSizeId));
        setTextColor(mIconTint);
    }
}

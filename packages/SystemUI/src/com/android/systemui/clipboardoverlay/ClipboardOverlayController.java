/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_SHOW_ACTIONS;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_SHOW_EDIT_BUTTON;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_SHOWN;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISSED_OTHER;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISS_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_EDIT_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHARE_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SWIPE_DISMISSED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TAP_OUTSIDE;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_TIMED_OUT;
import static com.android.systemui.flags.Flags.CLIPBOARD_REMOTE_BEHAVIOR;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule.OverlayWindowContext;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.screenshot.TimeoutHandler;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controls state and UI for the overlay that appears when something is added to the clipboard
 */
public class ClipboardOverlayController implements ClipboardListener.ClipboardOverlay {
    private static final String TAG = "ClipboardOverlayCtrlr";

    /** Constants for screenshot/copy deconflicting */
    public static final String SCREENSHOT_ACTION = "com.android.systemui.SCREENSHOT";
    public static final String SELF_PERMISSION = "com.android.systemui.permission.SELF";
    public static final String COPY_OVERLAY_ACTION = "com.android.systemui.COPY";

    private static final int CLIPBOARD_DEFAULT_TIMEOUT_MILLIS = 6000;

    private final Context mContext;
    private final ClipboardLogger mClipboardLogger;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final DisplayManager mDisplayManager;
    private final ClipboardOverlayWindow mWindow;
    private final TimeoutHandler mTimeoutHandler;
    private final ClipboardOverlayUtils mClipboardUtils;
    private final FeatureFlags mFeatureFlags;
    private final Executor mBgExecutor;

    private final ClipboardOverlayView mView;

    private Runnable mOnSessionCompleteListener;
    private Runnable mOnRemoteCopyTapped;
    private Runnable mOnShareTapped;
    private Runnable mOnEditTapped;
    private Runnable mOnPreviewTapped;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private BroadcastReceiver mCloseDialogsReceiver;
    private BroadcastReceiver mScreenshotReceiver;

    private Animator mExitAnimator;
    private Animator mEnterAnimator;

    private Runnable mOnUiUpdate;

    private final ClipboardOverlayView.ClipboardOverlayCallbacks mClipboardCallbacks =
            new ClipboardOverlayView.ClipboardOverlayCallbacks() {
                @Override
                public void onInteraction() {
                    if (mOnUiUpdate != null) {
                        mOnUiUpdate.run();
                    }
                }

                @Override
                public void onSwipeDismissInitiated(Animator animator) {
                    mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_SWIPE_DISMISSED);
                    mExitAnimator = animator;
                }

                @Override
                public void onDismissComplete() {
                    hideImmediate();
                }

                @Override
                public void onPreviewTapped() {
                    if (mOnPreviewTapped != null) {
                        mOnPreviewTapped.run();
                    }
                }

                @Override
                public void onShareButtonTapped() {
                    if (mOnShareTapped != null) {
                        mOnShareTapped.run();
                    }
                }

                @Override
                public void onEditButtonTapped() {
                    if (mOnEditTapped != null) {
                        mOnEditTapped.run();
                    }
                }

                @Override
                public void onRemoteCopyButtonTapped() {
                    if (mOnRemoteCopyTapped != null) {
                        mOnRemoteCopyTapped.run();
                    }
                }

                @Override
                public void onDismissButtonTapped() {
                    mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
                    animateOut();
                }
            };

    @Inject
    public ClipboardOverlayController(@OverlayWindowContext Context context,
            ClipboardOverlayView clipboardOverlayView,
            ClipboardOverlayWindow clipboardOverlayWindow,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            TimeoutHandler timeoutHandler,
            FeatureFlags featureFlags,
            ClipboardOverlayUtils clipboardUtils,
            @Background Executor bgExecutor,
            UiEventLogger uiEventLogger) {
        mBroadcastDispatcher = broadcastDispatcher;
        mDisplayManager = requireNonNull(context.getSystemService(DisplayManager.class));
        final Context displayContext = context.createDisplayContext(getDefaultDisplay());
        mContext = displayContext.createWindowContext(TYPE_SCREENSHOT, null);

        mClipboardLogger = new ClipboardLogger(uiEventLogger);

        mView = clipboardOverlayView;
        mWindow = clipboardOverlayWindow;
        mWindow.init(mView::setInsets, () -> {
            mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
            hideImmediate();
        });

        mTimeoutHandler = timeoutHandler;
        mTimeoutHandler.setDefaultTimeoutMillis(CLIPBOARD_DEFAULT_TIMEOUT_MILLIS);

        mFeatureFlags = featureFlags;
        mClipboardUtils = clipboardUtils;
        mBgExecutor = bgExecutor;

        mView.setCallbacks(mClipboardCallbacks);


        mWindow.withWindowAttached(() -> {
            mWindow.setContentView(mView);
            mView.setInsets(mWindow.getWindowInsets(),
                    mContext.getResources().getConfiguration().orientation);
        });

        mTimeoutHandler.setOnTimeoutRunnable(() -> {
            mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_TIMED_OUT);
            animateOut();
        });

        mCloseDialogsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                    mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                    animateOut();
                }
            }
        };

        mBroadcastDispatcher.registerReceiver(mCloseDialogsReceiver,
                new IntentFilter(ACTION_CLOSE_SYSTEM_DIALOGS));
        mScreenshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SCREENSHOT_ACTION.equals(intent.getAction())) {
                    mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_DISMISSED_OTHER);
                    animateOut();
                }
            }
        };

        mBroadcastDispatcher.registerReceiver(mScreenshotReceiver,
                new IntentFilter(SCREENSHOT_ACTION), null, null, Context.RECEIVER_EXPORTED,
                SELF_PERMISSION);
        monitorOutsideTouches();

        Intent copyIntent = new Intent(COPY_OVERLAY_ACTION);
        // Set package name so the system knows it's safe
        copyIntent.setPackage(mContext.getPackageName());
        broadcastSender.sendBroadcast(copyIntent, SELF_PERMISSION);
    }

    @Override // ClipboardListener.ClipboardOverlay
    public void setClipData(ClipData clipData, String clipSource) {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            mExitAnimator.cancel();
        }
        reset();
        mClipboardLogger.setClipSource(clipSource);
        String accessibilityAnnouncement = mContext.getString(R.string.clipboard_content_copied);

        boolean isSensitive = clipData != null && clipData.getDescription().getExtras() != null
                && clipData.getDescription().getExtras()
                .getBoolean(ClipDescription.EXTRA_IS_SENSITIVE);
        boolean isRemote = mFeatureFlags.isEnabled(CLIPBOARD_REMOTE_BEHAVIOR)
                && mClipboardUtils.isRemoteCopy(mContext, clipData, clipSource);
        if (clipData == null || clipData.getItemCount() == 0) {
            mView.showDefaultTextPreview();
        } else if (!TextUtils.isEmpty(clipData.getItemAt(0).getText())) {
            ClipData.Item item = clipData.getItemAt(0);
            if (isRemote || DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                    CLIPBOARD_OVERLAY_SHOW_ACTIONS, false)) {
                if (item.getTextLinks() != null) {
                    classifyText(clipData.getItemAt(0), clipSource);
                }
            }
            if (isSensitive) {
                showEditableText(mContext.getString(R.string.clipboard_asterisks), true);
            } else {
                showEditableText(item.getText(), false);
            }
            mOnShareTapped = () -> shareContent(clipData);
            mView.showShareChip();
            accessibilityAnnouncement = mContext.getString(R.string.clipboard_text_copied);
        } else if (clipData.getItemAt(0).getUri() != null) {
            if (tryShowEditableImage(clipData.getItemAt(0).getUri(), isSensitive)) {
                mOnShareTapped = () -> shareContent(clipData);
                mView.showShareChip();
                accessibilityAnnouncement = mContext.getString(R.string.clipboard_image_copied);
            }
        } else {
            mView.showDefaultTextPreview();
        }
        if (!isRemote) {
            maybeShowRemoteCopy(clipData);
        }
        animateIn();
        mView.announceForAccessibility(accessibilityAnnouncement);
        if (isRemote) {
            mTimeoutHandler.cancelTimeout();
            mOnUiUpdate = null;
        } else {
            mOnUiUpdate = mTimeoutHandler::resetTimeout;
            mOnUiUpdate.run();
        }
    }

    private void maybeShowRemoteCopy(ClipData clipData) {
        Intent remoteCopyIntent = IntentCreator.getRemoteCopyIntent(clipData, mContext);
        // Only show remote copy if it's available.
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager.resolveActivity(
                remoteCopyIntent, PackageManager.ResolveInfoFlags.of(0)) != null) {
            mView.setRemoteCopyVisibility(true);
            mOnRemoteCopyTapped = () -> {
                mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_REMOTE_COPY_TAPPED);
                mContext.startActivity(remoteCopyIntent);
                animateOut();
            };
        } else {
            mView.setRemoteCopyVisibility(false);
        }
    }

    @Override // ClipboardListener.ClipboardOverlay
    public void setOnSessionCompleteListener(Runnable runnable) {
        mOnSessionCompleteListener = runnable;
    }

    private void classifyText(ClipData.Item item, String source) {
        mBgExecutor.execute(() -> {
            Optional<RemoteAction> action = mClipboardUtils.getAction(item, source);
            mView.post(() -> {
                mView.resetActionChips();
                action.ifPresent(remoteAction -> {
                    mView.setActionChip(remoteAction, () -> {
                        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_ACTION_TAPPED);
                        animateOut();
                    });
                    mClipboardLogger.logUnguarded(CLIPBOARD_OVERLAY_ACTION_SHOWN);
                });
            });
        });
    }

    private void monitorOutsideTouches() {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        mInputMonitor = inputManager.monitorGestureInput("clipboard overlay", 0);
        mInputEventReceiver = new InputEventReceiver(
                mInputMonitor.getInputChannel(), Looper.getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                if (event instanceof MotionEvent) {
                    MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (!mView.isInTouchRegion(
                                (int) motionEvent.getRawX(), (int) motionEvent.getRawY())) {
                            mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_TAP_OUTSIDE);
                            animateOut();
                        }
                    }
                }
                finishInputEvent(event, true /* handled */);
            }
        };
    }

    private void editImage(Uri uri) {
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_EDIT_TAPPED);
        mContext.startActivity(IntentCreator.getImageEditIntent(uri, mContext));
        animateOut();
    }

    private void editText() {
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_EDIT_TAPPED);
        mContext.startActivity(IntentCreator.getTextEditorIntent(mContext));
        animateOut();
    }

    private void shareContent(ClipData clip) {
        mClipboardLogger.logSessionComplete(CLIPBOARD_OVERLAY_SHARE_TAPPED);
        mContext.startActivity(IntentCreator.getShareIntent(clip, mContext));
        animateOut();
    }

    private void showEditableText(CharSequence text, boolean hidden) {
        mView.showTextPreview(text, hidden);
        mView.setEditAccessibilityAction(true);
        mOnPreviewTapped = this::editText;
        if (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                CLIPBOARD_OVERLAY_SHOW_EDIT_BUTTON, false)) {
            mOnEditTapped = this::editText;
            mView.showEditChip(mContext.getString(R.string.clipboard_edit_text_description));
        }
    }

    private boolean tryShowEditableImage(Uri uri, boolean isSensitive) {
        Runnable listener = () -> editImage(uri);
        ContentResolver resolver = mContext.getContentResolver();
        String mimeType = resolver.getType(uri);
        boolean isEditableImage = mimeType != null && mimeType.startsWith("image");
        if (isSensitive) {
            mView.showImagePreview(null);
            if (isEditableImage) {
                mOnPreviewTapped = listener;
                mView.setEditAccessibilityAction(true);
            }
        } else if (isEditableImage) { // if the MIMEtype is image, try to load
            try {
                int size = mContext.getResources().getDimensionPixelSize(R.dimen.overlay_x_scale);
                // The width of the view is capped, height maintains aspect ratio, so allow it to be
                // taller if needed.
                Bitmap thumbnail = resolver.loadThumbnail(uri, new Size(size, size * 4), null);
                mView.showImagePreview(thumbnail);
                mView.setEditAccessibilityAction(true);
                mOnPreviewTapped = listener;
            } catch (IOException e) {
                Log.e(TAG, "Thumbnail loading failed", e);
                mView.showDefaultTextPreview();
                isEditableImage = false;
            }
        } else {
            mView.showDefaultTextPreview();
        }
        if (isEditableImage && DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_SHOW_EDIT_BUTTON, false)) {
            mView.showEditChip(mContext.getString(R.string.clipboard_edit_image_description));
        }
        return isEditableImage;
    }

    private void animateIn() {
        if (mEnterAnimator != null && mEnterAnimator.isRunning()) {
            return;
        }
        mEnterAnimator = mView.getEnterAnimation();
        mEnterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (mOnUiUpdate != null) {
                    mOnUiUpdate.run();
                }
            }
        });
        mEnterAnimator.start();
    }

    private void animateOut() {
        if (mExitAnimator != null && mExitAnimator.isRunning()) {
            return;
        }
        Animator anim = mView.getExitAnimation();
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (!mCancelled) {
                    hideImmediate();
                }
            }
        });
        mExitAnimator = anim;
        anim.start();
    }

    void hideImmediate() {
        // Note this may be called multiple times if multiple dismissal events happen at the same
        // time.
        mTimeoutHandler.cancelTimeout();
        mWindow.remove();
        if (mCloseDialogsReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mCloseDialogsReceiver);
            mCloseDialogsReceiver = null;
        }
        if (mScreenshotReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mScreenshotReceiver);
            mScreenshotReceiver = null;
        }
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
        if (mOnSessionCompleteListener != null) {
            mOnSessionCompleteListener.run();
        }
    }

    private void reset() {
        mOnRemoteCopyTapped = null;
        mOnShareTapped = null;
        mOnEditTapped = null;
        mOnPreviewTapped = null;
        mView.reset();
        mTimeoutHandler.cancelTimeout();
        mClipboardLogger.reset();
    }

    private Display getDefaultDisplay() {
        return mDisplayManager.getDisplay(DEFAULT_DISPLAY);
    }

    static class ClipboardLogger {
        private final UiEventLogger mUiEventLogger;
        private String mClipSource;
        private boolean mGuarded = false;

        ClipboardLogger(UiEventLogger uiEventLogger) {
            mUiEventLogger = uiEventLogger;
        }

        void setClipSource(String clipSource) {
            mClipSource = clipSource;
        }

        void logUnguarded(@NonNull UiEventLogger.UiEventEnum event) {
            mUiEventLogger.log(event, 0, mClipSource);
        }

        void logSessionComplete(@NonNull UiEventLogger.UiEventEnum event) {
            if (!mGuarded) {
                mGuarded = true;
                mUiEventLogger.log(event, 0, mClipSource);
            }
        }

        void reset() {
            mGuarded = false;
            mClipSource = null;
        }
    }
}

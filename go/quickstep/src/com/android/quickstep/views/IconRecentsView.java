/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.quickstep.views;

import static androidx.recyclerview.widget.LinearLayoutManager.VERTICAL;

import static com.android.quickstep.TaskAdapter.CHANGE_EVENT_TYPE_EMPTY_TO_CONTENT;
import static com.android.quickstep.views.TaskLayoutUtils.getTaskListHeight;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver;
import androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener;

import com.android.launcher3.BaseActivity;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.quickstep.ContentFillItemAnimator;
import com.android.quickstep.RecentsModel;
import com.android.quickstep.RecentsToActivityHelper;
import com.android.quickstep.TaskActionController;
import com.android.quickstep.TaskAdapter;
import com.android.quickstep.TaskHolder;
import com.android.quickstep.TaskLayoutManager;
import com.android.quickstep.TaskListLoader;
import com.android.quickstep.TaskSwipeCallback;
import com.android.systemui.shared.recents.model.Task;

import java.util.Optional;

/**
 * Root view for the icon recents view. Acts as the main interface to the rest of the Launcher code
 * base.
 */
public final class IconRecentsView extends FrameLayout {

    public static final FloatProperty<IconRecentsView> CONTENT_ALPHA =
            new FloatProperty<IconRecentsView>("contentAlpha") {
                @Override
                public void setValue(IconRecentsView view, float v) {
                    ALPHA.set(view, v);
                    if (view.getVisibility() != VISIBLE && v > 0) {
                        view.setVisibility(VISIBLE);
                    } else if (view.getVisibility() != GONE && v == 0){
                        view.setVisibility(GONE);
                    }
                }

                @Override
                public Float get(IconRecentsView view) {
                    return ALPHA.get(view);
                }
            };
    private static final long CROSSFADE_DURATION = 300;
    private static final long LAYOUT_ITEM_ANIMATE_IN_DURATION = 150;
    private static final long LAYOUT_ITEM_ANIMATE_IN_DELAY_BETWEEN = 40;
    private static final long ITEM_ANIMATE_OUT_DURATION = 150;
    private static final long ITEM_ANIMATE_OUT_DELAY_BETWEEN = 40;
    private static final float ITEM_ANIMATE_OUT_TRANSLATION_X_RATIO = .25f;
    private static final long CLEAR_ALL_FADE_DELAY = 120;

    /**
     * A ratio representing the view's relative placement within its padded space. For example, 0
     * is top aligned and 0.5 is centered vertically.
     */
    @ViewDebug.ExportedProperty(category = "launcher")

    private final Context mContext;
    private final TaskListLoader mTaskLoader;
    private final TaskAdapter mTaskAdapter;
    private final TaskActionController mTaskActionController;
    private final DefaultItemAnimator mDefaultItemAnimator = new DefaultItemAnimator();
    private final ContentFillItemAnimator mLoadingContentItemAnimator =
            new ContentFillItemAnimator();
    private final DeviceProfile mDeviceProfile;

    private RecentsToActivityHelper mActivityHelper;
    private RecyclerView mTaskRecyclerView;
    private View mShowingContentView;
    private View mEmptyView;
    private View mContentView;
    private boolean mTransitionedFromApp;
    private AnimatorSet mLayoutAnimation;
    private final ArraySet<View> mLayingOutViews = new ArraySet<>();
    private final RecentsModel.TaskThumbnailChangeListener listener = (taskId, thumbnailData) -> {
        TaskItemView[] itemViews = getTaskViews();
        for (TaskItemView taskView : itemViews) {
            TaskHolder taskHolder = (TaskHolder) mTaskRecyclerView.getChildViewHolder(taskView);
            Optional<Task> optTask = taskHolder.getTask();
            if (optTask.filter(task -> task.key.id == taskId).isPresent()) {
                Task task = optTask.get();
                // Update thumbnail on the task.
                task.thumbnail = thumbnailData;
                taskView.setThumbnail(thumbnailData.thumbnail);
                return task;
            }
        }
        return null;
    };

    public IconRecentsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        BaseActivity activity = BaseActivity.fromContext(context);
        mContext = context;
        mDeviceProfile = activity.getDeviceProfile();
        mTaskLoader = new TaskListLoader(mContext);
        mTaskAdapter = new TaskAdapter(mTaskLoader);
        mTaskAdapter.setOnClearAllClickListener(view -> animateClearAllTasks());
        mTaskActionController = new TaskActionController(mTaskLoader, mTaskAdapter);
        mTaskAdapter.setActionController(mTaskActionController);
        RecentsModel.INSTANCE.get(context).addThumbnailChangeListener(listener);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (mTaskRecyclerView == null) {
            mTaskRecyclerView = findViewById(R.id.recent_task_recycler_view);
            ViewGroup.LayoutParams recyclerViewParams = mTaskRecyclerView.getLayoutParams();
            recyclerViewParams.height = getTaskListHeight(mDeviceProfile);
            mTaskRecyclerView.setAdapter(mTaskAdapter);
            mTaskRecyclerView.setLayoutManager(
                    new TaskLayoutManager(mContext, VERTICAL, true /* reverseLayout */));
            ItemTouchHelper helper = new ItemTouchHelper(
                    new TaskSwipeCallback(mTaskActionController));
            helper.attachToRecyclerView(mTaskRecyclerView);
            mTaskRecyclerView.addOnChildAttachStateChangeListener(
                    new OnChildAttachStateChangeListener() {
                        @Override
                        public void onChildViewAttachedToWindow(@NonNull View view) {
                            if (mLayoutAnimation != null && !mLayingOutViews.contains(view)) {
                                // Child view was added that is not part of current layout animation
                                // so restart the animation.
                                animateFadeInLayoutAnimation();
                            }
                        }

                        @Override
                        public void onChildViewDetachedFromWindow(@NonNull View view) { }
                    });
            mTaskRecyclerView.setItemAnimator(mDefaultItemAnimator);
            mLoadingContentItemAnimator.setOnAnimationFinishedRunnable(
                    () -> mTaskRecyclerView.setItemAnimator(new DefaultItemAnimator()));

            mEmptyView = findViewById(R.id.recent_task_empty_view);
            mContentView = mTaskRecyclerView;
            mTaskAdapter.registerAdapterDataObserver(new AdapterDataObserver() {
                @Override
                public void onChanged() {
                    updateContentViewVisibility();
                }

                @Override
                public void onItemRangeRemoved(int positionStart, int itemCount) {
                    updateContentViewVisibility();
                }
            });
            // TODO: Move layout param logic into onMeasure
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        TaskItemView[] itemViews = getTaskViews();
        for (TaskItemView itemView : itemViews) {
            itemView.setEnabled(enabled);
        }
        // TODO: Disable clear all button.
    }

    /**
     * Set activity helper for the view to callback to.
     *
     * @param helper the activity helper
     */
    public void setRecentsToActivityHelper(@NonNull RecentsToActivityHelper helper) {
        mActivityHelper = helper;
    }

    /**
     * Logic for when we know we are going to overview/recents and will be putting up the recents
     * view. This should be used to prepare recents (e.g. load any task data, etc.) before it
     * becomes visible.
     */
    public void onBeginTransitionToOverview() {
        scheduleFadeInLayoutAnimation();
        // Load any task changes
        if (!mTaskLoader.needsToLoad()) {
            return;
        }
        mTaskAdapter.setIsShowingLoadingUi(true);
        mTaskAdapter.notifyDataSetChanged();
        mTaskLoader.loadTaskList(tasks -> {
            int numEmptyItems = mTaskAdapter.getItemCount();
            mTaskAdapter.setIsShowingLoadingUi(false);
            int numActualItems = mTaskAdapter.getItemCount();
            if (numEmptyItems < numActualItems) {
                throw new IllegalStateException("There are less empty item views than the number "
                        + "of items to animate to.");
            }
            // Possible that task list loads faster than adapter changes propagate to layout so
            // only start content fill animation if there aren't any pending adapter changes.
            if (!mTaskRecyclerView.hasPendingAdapterUpdates()) {
                // Set item animator for content filling animation. The item animator will switch
                // back to the default on completion
                mTaskRecyclerView.setItemAnimator(mLoadingContentItemAnimator);
            }
            mTaskAdapter.notifyItemRangeRemoved(numActualItems, numEmptyItems - numActualItems);
            mTaskAdapter.notifyItemRangeChanged(
                    0, numActualItems, CHANGE_EVENT_TYPE_EMPTY_TO_CONTENT);
        });
    }

    /**
     * Set whether we transitioned to recents from the most recent app.
     *
     * @param transitionedFromApp true if transitioned from the most recent app, false otherwise
     */
    public void setTransitionedFromApp(boolean transitionedFromApp) {
        mTransitionedFromApp = transitionedFromApp;
    }

    /**
     * Handles input from the overview button. Launch the most recent task unless we just came from
     * the app. In that case, we launch the next most recent.
     */
    public void handleOverviewCommand() {
        int childCount = mTaskRecyclerView.getChildCount();
        if (childCount == 0) {
            // Do nothing
            return;
        }
        TaskHolder taskToLaunch;
        if (mTransitionedFromApp && childCount > 1) {
            // Launch the next most recent app
            TaskItemView itemView = (TaskItemView) mTaskRecyclerView.getChildAt(1);
            taskToLaunch = (TaskHolder) mTaskRecyclerView.getChildViewHolder(itemView);
        } else {
            // Launch the most recent app
            TaskItemView itemView = (TaskItemView) mTaskRecyclerView.getChildAt(0);
            taskToLaunch = (TaskHolder) mTaskRecyclerView.getChildViewHolder(itemView);
        }
        mTaskActionController.launchTask(taskToLaunch);
    }

    /**
     * Get the bottom most thumbnail view to animate to.
     *
     * @return the thumbnail view if laid out
     */
    public @Nullable View getBottomThumbnailView() {
        if (mTaskRecyclerView.getChildCount() == 0) {
            return null;
        }
        TaskItemView view = (TaskItemView) mTaskRecyclerView.getChildAt(0);
        return view.getThumbnailView();
    }

    /**
     * Clear all tasks and animate out.
     */
    private void animateClearAllTasks() {
        setEnabled(false);
        TaskItemView[] itemViews = getTaskViews();

        AnimatorSet clearAnim = new AnimatorSet();
        long currentDelay = 0;

        // Animate each item view to the right and fade out.
        for (TaskItemView itemView : itemViews) {
            PropertyValuesHolder transXproperty = PropertyValuesHolder.ofFloat(TRANSLATION_X,
                    0, itemView.getWidth() * ITEM_ANIMATE_OUT_TRANSLATION_X_RATIO);
            PropertyValuesHolder alphaProperty = PropertyValuesHolder.ofFloat(ALPHA, 1.0f, 0f);
            ObjectAnimator itemAnim = ObjectAnimator.ofPropertyValuesHolder(itemView,
                    transXproperty, alphaProperty);
            itemAnim.setDuration(ITEM_ANIMATE_OUT_DURATION);
            itemAnim.setStartDelay(currentDelay);

            clearAnim.play(itemAnim);
            currentDelay += ITEM_ANIMATE_OUT_DELAY_BETWEEN;
        }

        // Animate view fading and leave recents when faded enough.
        ValueAnimator contentAlpha = ValueAnimator.ofFloat(1.0f, 0f)
                .setDuration(CROSSFADE_DURATION);
        contentAlpha.setStartDelay(CLEAR_ALL_FADE_DELAY);
        contentAlpha.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            private boolean mLeftRecents = false;

            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                mContentView.setAlpha((float) valueAnimator.getAnimatedValue());
                // Leave recents while fading out.
                if ((float) valueAnimator.getAnimatedValue() < .5f && !mLeftRecents) {
                    mActivityHelper.leaveRecents();
                    mLeftRecents = true;
                }
            }
        });

        clearAnim.play(contentAlpha);
        clearAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (TaskItemView itemView : itemViews) {
                    itemView.setTranslationX(0);
                    itemView.setAlpha(1.0f);
                }
                setEnabled(true);
                mContentView.setVisibility(GONE);
                mTaskActionController.clearAllTasks();
            }
        });
        clearAnim.start();
    }

    /**
     * Get attached task item views ordered by most recent.
     *
     * @return array of attached task item views
     */
    private TaskItemView[] getTaskViews() {
        // TODO: Check that clear all button isn't here..
        int taskCount = mTaskRecyclerView.getChildCount();
        TaskItemView[] itemViews = new TaskItemView[taskCount];
        for (int i = 0; i < taskCount; i ++) {
            itemViews[i] = (TaskItemView) mTaskRecyclerView.getChildAt(i);
        }
        return itemViews;
    }

    /**
     * Update the content view so that the appropriate view is shown based off the current list
     * of tasks.
     */
    private void updateContentViewVisibility() {
        int taskListSize = mTaskAdapter.getItemCount();
        if (mShowingContentView != mEmptyView && taskListSize == 0) {
            mShowingContentView = mEmptyView;
            crossfadeViews(mEmptyView, mContentView);
            mActivityHelper.leaveRecents();
        }
        if (mShowingContentView != mContentView && taskListSize > 0) {
            mShowingContentView = mContentView;
            crossfadeViews(mContentView, mEmptyView);
        }
    }

    /**
     * Animate views so that one view fades in while the other fades out.
     *
     * @param fadeInView view that should fade in
     * @param fadeOutView view that should fade out
     */
    private void crossfadeViews(View fadeInView, View fadeOutView) {
        fadeInView.animate().cancel();
        fadeInView.setVisibility(VISIBLE);
        fadeInView.setAlpha(0f);
        fadeInView.animate()
                .alpha(1f)
                .setDuration(CROSSFADE_DURATION)
                .setListener(null);

        fadeOutView.animate().cancel();
        fadeOutView.animate()
                .alpha(0f)
                .setDuration(CROSSFADE_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        fadeOutView.setVisibility(GONE);
                    }
                });
    }

    /**
     * Schedule a one-shot layout animation on the next layout. Separate from
     * {@link #scheduleLayoutAnimation()} as the animation is {@link Animator} based and acts on the
     * view properties themselves, allowing more controllable behavior and making it easier to
     * manage when the animation conflicts with another animation.
     */
    private void scheduleFadeInLayoutAnimation() {
        mTaskRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        animateFadeInLayoutAnimation();
                        mTaskRecyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });
    }

    /**
     * Start animating the layout animation where items fade in.
     */
    private void animateFadeInLayoutAnimation() {
        if (mLayoutAnimation != null) {
            // If layout animation still in progress, cancel and restart.
            mLayoutAnimation.cancel();
        }
        TaskItemView[] views = getTaskViews();
        int delay = 0;
        mLayoutAnimation = new AnimatorSet();
        for (TaskItemView view : views) {
            view.setAlpha(0.0f);
            Animator alphaAnim = ObjectAnimator.ofFloat(view, ALPHA, 0.0f, 1.0f);
            alphaAnim.setDuration(LAYOUT_ITEM_ANIMATE_IN_DURATION).setStartDelay(delay);
            alphaAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.setAlpha(1.0f);
                    mLayingOutViews.remove(view);
                }
            });
            delay += LAYOUT_ITEM_ANIMATE_IN_DELAY_BETWEEN;
            mLayoutAnimation.play(alphaAnim);
            mLayingOutViews.add(view);
        }
        mLayoutAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mLayoutAnimation = null;
            }
        });
        mLayoutAnimation.start();
    }
}

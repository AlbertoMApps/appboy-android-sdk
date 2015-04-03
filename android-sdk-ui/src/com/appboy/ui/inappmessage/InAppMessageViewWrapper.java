package com.appboy.ui.inappmessage;

import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;

import com.appboy.Constants;
import com.appboy.enums.inappmessage.ClickAction;
import com.appboy.enums.inappmessage.DismissType;
import com.appboy.enums.inappmessage.SlideFrom;
import com.appboy.models.IInAppMessage;
import com.appboy.models.IInAppMessageImmersive;
import com.appboy.models.InAppMessageSlideup;
import com.appboy.models.MessageButton;
import com.appboy.ui.support.ViewUtils;

import java.util.List;

public class InAppMessageViewWrapper {
  private static final String TAG = String.format("%s.%s", Constants.APPBOY_LOG_TAG_PREFIX, InAppMessageViewWrapper.class.getName());
  private static Interpolator sInterpolator = new DecelerateInterpolator();
  private static final long sAnimationDurationInMilliseconds = 400l;

  private final View mInAppMessageView;
  private View mClickableInAppMessageView;
  private View mCloseButton;
  private List<View> mButtons;
  private final IInAppMessage mInAppMessage;
  private final IInAppMessageViewLifecycleListener mInAppMessageViewLifecycleListener;
  private Runnable mDismissRunnable;
  private boolean mIsAnimatingClose;
  private int mShortAnimationDuration;

  /**
   * Constructor for base ands slideup view wrappers.  Adds click listeners to the in-app message view and
   * adds swipe functionality to slideup in-app messages.
   *
   * @param inAppMessageView In-app message top level view.
   * @param inAppMessage In-app message model.
   * @param inAppMessageViewLifecycleListener In-app message lifecycle listener.
   * @param clickableInAppMessageView View for which click actions apply. Clicking any part of the top level view
   *                                  outside this view will close the in-app message. In many cases, the clickable
   *                                  view is the top level view itself.
   */
  public InAppMessageViewWrapper(View inAppMessageView, IInAppMessage inAppMessage, IInAppMessageViewLifecycleListener inAppMessageViewLifecycleListener, View clickableInAppMessageView) {
    mInAppMessageView = inAppMessageView;
    mInAppMessage = inAppMessage;
    mInAppMessageViewLifecycleListener = inAppMessageViewLifecycleListener;
    mIsAnimatingClose = false;
    mShortAnimationDuration = inAppMessageView.getResources().getInteger(android.R.integer.config_shortAnimTime);
    if (clickableInAppMessageView != null) {
      mClickableInAppMessageView = clickableInAppMessageView;
    } else {
      mClickableInAppMessageView = mInAppMessageView;
    }

    // We only apply the swipe touch listener to slideup in-app message Views on devices running Android version
    // 12 or higher. Pre-12 devices will have to click to close the slideup in-app message.
    // Only slideup in-app messages can be swiped.
    if (Build.VERSION.SDK_INT >= 12 && mInAppMessage instanceof InAppMessageSlideup) {
      // Adds the swipe listener to the in-app message View. All slideup in-app messages should be dismissable via a swipe
      // (even auto close slideup in-app messages).
      SwipeDismissTouchListener.DismissCallbacks dismissCallbacks = createDismissCallbacks();
      TouchAwareSwipeDismissTouchListener touchAwareSwipeListener = new TouchAwareSwipeDismissTouchListener(inAppMessageView, null, dismissCallbacks);
      // We set a custom touch listener that cancel the auto close runnable when touched and adds
      // a new runnable when the touch ends.
      touchAwareSwipeListener.setTouchListener(createTouchAwareListener());
      mClickableInAppMessageView.setOnTouchListener(touchAwareSwipeListener);
    } else if (mInAppMessage instanceof InAppMessageSlideup) {
      mClickableInAppMessageView.setOnTouchListener(getSimpleSwipeListener());
    }

    // Set click listener on clickable in-app message view
    mClickableInAppMessageView.setOnClickListener(createClickListener());
  }

  /**
   * Constructor for immersive in-app message view wrappers.  Adds listeners to an optional close button and
   * message button views.
   *
   * @param inAppMessageView
   * @param inAppMessage
   * @param inAppMessageViewLifecycleListener
   * @param clickableInAppMessageView
   * @param buttons List of views corresponding to MessageButton objects stored in the in-app message model object.
   *                These views should map one to one with the MessageButton objects.
   * @param closeButton
   */
  public InAppMessageViewWrapper(View inAppMessageView, IInAppMessage inAppMessage, IInAppMessageViewLifecycleListener inAppMessageViewLifecycleListener, View clickableInAppMessageView, List<View> buttons, View closeButton) {
    this(inAppMessageView, inAppMessage, inAppMessageViewLifecycleListener, clickableInAppMessageView);

    // Set close button click listener
    if (closeButton != null) {
      mCloseButton = closeButton;
      mCloseButton.setOnClickListener(createCloseInAppMessageClickListener());
    }

    // Set button click listeners
    if (buttons != null) {
      mButtons = buttons;
      for (View button : mButtons) {
        button.setOnClickListener(createButtonClickListener());
      }
    }
  }

  public void open(final FrameLayout root) {
    mInAppMessageViewLifecycleListener.beforeOpened(mInAppMessageView, mInAppMessage);
    addViewToLayout(root);
    display();
  }
  public boolean getIsAnimatingClose() {
    return mIsAnimatingClose;
  }
  public void callAfterClosed() {
    mInAppMessageViewLifecycleListener.afterClosed(mInAppMessage);
  }

  private void preClose() {
    mInAppMessageViewLifecycleListener.beforeClosed(mInAppMessageView, mInAppMessage);
  }

  private void performClose() {
    if (mInAppMessage.getAnimateOut()) {
      mIsAnimatingClose = true;
      setAndStartAnimation(false);
    } else {
      ViewUtils.removeViewFromParent(mInAppMessageView);
      mInAppMessageViewLifecycleListener.afterClosed(mInAppMessage);
    }
  }

  public void close() {
    preClose();
    performClose();
  }

  public View getInAppMessageView() {
    return mInAppMessageView;
  }

  public IInAppMessage getInAppMessage() {
    return mInAppMessage;
  }

  private View.OnClickListener createClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // The onClicked lifecycle method is called and it can be used to turn off the close animation.
        // Full and modal in-app messages can only be clicked directly when they do not contain buttons.
        // Slideup in-app messages are always clickable.
        if (mInAppMessage instanceof IInAppMessageImmersive) {
          IInAppMessageImmersive inAppMessageImmersive = (IInAppMessageImmersive) mInAppMessage;
          if (inAppMessageImmersive.getMessageButtons() == null || inAppMessageImmersive.getMessageButtons().size() == 0) {
            mInAppMessageViewLifecycleListener.onClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), mInAppMessageView, mInAppMessage);
          }
        } else {
          mInAppMessageViewLifecycleListener.onClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), mInAppMessageView, mInAppMessage);
        }
      }
    };
  }

  private View.OnClickListener createButtonClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // The onClicked lifecycle method is called and it can be used to turn off the close animation.
        MessageButton messageButton;
        IInAppMessageImmersive inAppMessageImmersive = (IInAppMessageImmersive) mInAppMessage;
        for (int i = 0; i < mButtons.size(); i++) {
          if (view.getId() == mButtons.get(i).getId()) {
            messageButton = inAppMessageImmersive.getMessageButtons().get(i);
            mInAppMessageViewLifecycleListener.onButtonClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), messageButton, inAppMessageImmersive);
            return;
          }
        }
      }
    };
  }

  private View.OnClickListener createCloseInAppMessageClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // The onClicked lifecycle method is called after setting the click action to none.
        mInAppMessage.setClickAction(ClickAction.NONE);
        mInAppMessageViewLifecycleListener.onClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), mInAppMessageView, mInAppMessage);
      }
    };
  }

  private void addViewToLayout(final FrameLayout root) {
    Log.d(TAG, "Adding In-app message view to root FrameLayout.");
    FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    if (mInAppMessage instanceof InAppMessageSlideup) {
      InAppMessageSlideup inAppMessageSlideup = (InAppMessageSlideup) mInAppMessage;
      layoutParams.gravity = inAppMessageSlideup.getSlideFrom() == SlideFrom.TOP ? Gravity.TOP : Gravity.BOTTOM;
    }
    if (mInAppMessage instanceof IInAppMessageImmersive) {
      mInAppMessageView.setFocusableInTouchMode(true);
      mInAppMessageView.requestFocus();
    }
    root.addView(mInAppMessageView, layoutParams);
  }

  private void removeViewFromLayout() {
    ViewUtils.removeViewFromParent(mInAppMessageView);
  }

  private void display() {
    if (mInAppMessage.getAnimateIn()) {
      Log.d(TAG, "In-app message view will animate into the visible area.");
      setAndStartAnimation(true);
      // The afterOpened lifecycle method gets called when the opening animation ends.
    } else {
      Log.d(TAG, "In-app message view will be placed instantly into the visible area.");
      // There is no opening animation, so we call the afterOpened lifecycle method immediately.
      if (mInAppMessage.getDismissType() == DismissType.AUTO_DISMISS) {
        addDismissRunnable();
      }
      mInAppMessageViewLifecycleListener.afterOpened(mInAppMessageView, mInAppMessage);
    }
  }

  private void addDismissRunnable() {
    if (mDismissRunnable == null) {
      mDismissRunnable = new Runnable() {
        @Override
        public void run() {
          close();
        }
      };
      mInAppMessageView.postDelayed(mDismissRunnable, mInAppMessage.getDurationInMilliseconds());
    }
  }

  private SwipeDismissTouchListener.DismissCallbacks createDismissCallbacks() {
    return new SwipeDismissTouchListener.DismissCallbacks() {
      @Override
      public boolean canDismiss(Object token) {
        return true;
      }
      @Override
      public void onDismiss(View view, Object token) {
        mInAppMessageViewLifecycleListener.onDismissed(mInAppMessageView, mInAppMessage);
        mInAppMessage.setAnimateOut(false);
        close();
      }
    };
  }

  private TouchAwareSwipeDismissTouchListener.ITouchListener createTouchAwareListener() {
    return new TouchAwareSwipeDismissTouchListener.ITouchListener() {
      @Override
      public void onTouchStartedOrContinued() {
        mInAppMessageView.removeCallbacks(mDismissRunnable);
      }
      @Override
      public void onTouchEnded() {
        if (mInAppMessage.getDismissType() == DismissType.AUTO_DISMISS) {
          addDismissRunnable();
        }
      }
    };
  }

  private Animation getFadeOutAnimation() {
    Animation fadeOut = new AlphaAnimation(1, 0);
    fadeOut.setInterpolator(new AccelerateInterpolator());
    fadeOut.setDuration(mShortAnimationDuration);
    return fadeOut;
  }

  private Animation getFadeInAnimation() {
    Animation fadeIn = new AlphaAnimation(0, 1);
    fadeIn.setInterpolator(new DecelerateInterpolator());
    fadeIn.setDuration(mShortAnimationDuration);
    return fadeIn;
  }

  /**
   * @param fromY Change in Y coordinate to apply at the start of the animation, represented as a percentage (where 1.0 is 100%).
   * @param toY Change in Y coordinate to apply at the end of the animation, represented as a percentage (where 1.0 is 100%).
   * @param duration Amount of time (in milliseconds) for the animation to run.
   * @return an Animation object with appropriate vertical transformation and duration
   */
  private Animation createVerticalAnimation(float fromY, float toY, long duration) {
    TranslateAnimation animation = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0f,
        Animation.RELATIVE_TO_PARENT, 0f, Animation.RELATIVE_TO_SELF, fromY,
        Animation.RELATIVE_TO_SELF, toY);
    return setAnimationParams(animation, duration);
  }

  /**
   * @param fromX Change in X coordinate to apply at the start of the animation, represented as a percentage (where 1.0 is 100%).
   * @param toX Change in X coordinate to apply at the end of the animation, represented as a percentage (where 1.0 is 100%).
   * @param duration Amount of time (in milliseconds) for the animation to run.
   * @return an Animation object with appropriate horizontal transformation and duration
   */
  private Animation createHorizontalAnimation(float fromX, float toX, long duration) {
    TranslateAnimation animation = new TranslateAnimation(Animation.RELATIVE_TO_SELF, fromX,
        Animation.RELATIVE_TO_SELF, toX, Animation.RELATIVE_TO_PARENT, 0f,
        Animation.RELATIVE_TO_PARENT, 0f);
    return setAnimationParams(animation, duration);
  }

  private Animation setAnimationParams(Animation animation, long duration) {
    animation.setDuration(duration);
    animation.setFillAfter(true);
    animation.setFillEnabled(true);
    animation.setInterpolator(sInterpolator);
    return animation;
  }

  /**
   * Instantiates and executes the correct animation for the current in-app message.  Slideup-type
   * messages slide in from the top or bottom of the view.  Other in-app messages fade in
   * and out of view.
   *
   * @param opening
   */
  private void setAndStartAnimation(boolean opening) {
    Animation animation;
    if (mInAppMessage instanceof InAppMessageSlideup) {
      InAppMessageSlideup inAppMessageSlideup = (InAppMessageSlideup) mInAppMessage;
      if (opening && inAppMessageSlideup.getSlideFrom() == SlideFrom.TOP) {
        animation = createVerticalAnimation(-1, 0, sAnimationDurationInMilliseconds);
      } else if (opening && inAppMessageSlideup.getSlideFrom() == SlideFrom.BOTTOM) {
        animation = createVerticalAnimation(1, 0, sAnimationDurationInMilliseconds);
      } else if (!opening && inAppMessageSlideup.getSlideFrom() == SlideFrom.TOP) {
        animation = createVerticalAnimation(0, -1, sAnimationDurationInMilliseconds);
      } else {
        animation = createVerticalAnimation(0, 1, sAnimationDurationInMilliseconds);
      }
    } else {
      if (opening) {
        animation = getFadeInAnimation();
      } else {
        animation = getFadeOutAnimation();
      }
    }
    animation.setAnimationListener(createAnimationListener(opening));
    mInAppMessageView.clearAnimation();
    mInAppMessageView.setAnimation(animation);
    animation.startNow();
    // We need to explicitly call invalidate on Froyo, otherwise the animation won't start :(
    mInAppMessageView.invalidate();
  }

  private Animation.AnimationListener createAnimationListener(boolean opening) {
    if (opening) {
      return new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
          mInAppMessageView.setClickable(false);
        }
        @Override
        public void onAnimationEnd(Animation animation) {
          mInAppMessageView.setVisibility(View.VISIBLE);
          mInAppMessageView.setClickable(true);
          if (mInAppMessage.getDismissType() == DismissType.AUTO_DISMISS) {
            addDismissRunnable();
          }
          Log.d(TAG, "In-app message animated into view.");
          mInAppMessageViewLifecycleListener.afterOpened(mInAppMessageView, mInAppMessage);
        }
        @Override
        public void onAnimationRepeat(Animation animation) {}
      };
    } else {
      return new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
          mInAppMessageView.setClickable(false);
        }
        @Override
        public void onAnimationEnd(Animation animation) {
          mInAppMessageView.clearAnimation();
          mInAppMessageView.setVisibility(View.GONE);
          mInAppMessageView.setClickable(true);
          removeViewFromLayout();
          mInAppMessageViewLifecycleListener.afterClosed(mInAppMessage);
        }
        @Override
        public void onAnimationRepeat(Animation animation) {}
      };
    }
  }

  /**
   * Adds swipe event handling to the SimpleSwipeDismissTouchListener.
   *
   * Used in API levels 11 and below.  Detected swipe left and right events
   * cause the slideup inapp message to animate off the screen in the direction of the swipe.
   */
  private SimpleSwipeDismissTouchListener getSimpleSwipeListener() {
    return new SimpleSwipeDismissTouchListener(mInAppMessageView.getContext()) {
      @Override
      public void onSwipeLeft() {
        animateAndClose(createHorizontalAnimation(0, -1, sAnimationDurationInMilliseconds));
      }

      @Override
      public void onSwipeRight() {
        animateAndClose(createHorizontalAnimation(0, 1, sAnimationDurationInMilliseconds));
      }

      private void animateAndClose(Animation animation) {
        preClose();
        mInAppMessageView.clearAnimation();
        mInAppMessageView.setAnimation(animation);
        animation.startNow();
        mInAppMessageView.invalidate();
        mInAppMessageViewLifecycleListener.onDismissed(mInAppMessageView, mInAppMessage);
        mInAppMessage.setAnimateOut(false);
        performClose();
      }
    };
  }
}
